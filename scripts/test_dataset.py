import csv
import tempfile
import unittest
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path
from unittest.mock import patch

import dataset


class DatasetTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        with patch.object(dataset, "PROPERTIES", 20):
            dataset.generate(cls.directory)

    def rows(self, table):
        with (self.directory / f"{table}.csv").open(encoding="utf-8", newline="") as source:
            yield from csv.reader(source)

    def test_counts_and_corrections(self):
        self.assertEqual(20, sum(1 for _ in self.rows("dim_realty")))
        self.assertEqual(20, sum(1 for _ in self.rows("dim_users")))
        self.assertEqual(22, sum(1 for _ in self.rows("dim_gauges")))
        self.assertEqual(720, sum(1 for _ in self.rows("fact_invoice")))
        counts = Counter((row[0], row[4]) for row in self.rows("fact_meter_readings"))
        self.assertEqual(20 * dataset.DAYS, len(counts))
        self.assertTrue(all(count in (1, 2) for count in counts.values()))
        self.assertAlmostEqual(0.03, sum(count == 2 for count in counts.values()) / len(counts), places=3)

    def test_invoices_match_corrected_consumption_and_historical_tariffs(self):
        latest = {(row[0], row[4]): row for row in self.rows("fact_meter_readings")}
        previous = {}
        usage = defaultdict(lambda: [0, 0])
        for (realty, day), row in latest.items():
            gauge, daytime, nighttime = row[3], int(row[5]), int(row[6])
            old_day, old_night = previous.get((realty, gauge), (0, 0))
            usage[realty, day[:7]][0] += daytime - old_day
            usage[realty, day[:7]][1] += nighttime - old_night
            previous[realty, gauge] = daytime, nighttime
        for row in self.rows("fact_invoice"):
            issued = date.fromisoformat(row[8][:10])
            tariff = next(t for t in dataset.TARIFFS if t.start <= issued < t.end)
            daytime, nighttime = usage[row[0], row[8][:7]]
            self.assertEqual((daytime, nighttime), (int(row[4]), int(row[5])))
            self.assertEqual(tariff.price(daytime, nighttime), int(row[6]))
            self.assertEqual((issued.replace(day=28) + dataset.timedelta(days=4)).replace(day=10).isoformat(), row[7])

    def test_binding_cancellation_leaves_one_active_meter_per_property(self):
        bindings = Counter()
        for gauge, realty, created, sign, version in self.rows("fact_gauge_bindings"):
            bindings[realty, gauge, created, version] += int(sign)
        active = [key for key, sign in bindings.items() if sign == 1]
        self.assertEqual(20, len(active))
        self.assertEqual(20, len({key[0] for key in active}))
        self.assertEqual(1, sum(key[3] == "2" for key in active))

    def test_generation_is_reproducible(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(dataset, "PROPERTIES", 20):
            destination = Path(directory)
            dataset.generate(destination)
            for table in dataset.TABLES:
                self.assertEqual((self.directory / f"{table}.csv").read_bytes(),
                                 (destination / f"{table}.csv").read_bytes())

    def test_failed_generation_does_not_leave_completion_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            (destination / ".generated").touch()
            with patch.object(dataset, "write_usage", side_effect=OSError("disk full")):
                with self.assertRaises(OSError):
                    dataset.generate(destination)
            self.assertFalse((destination / ".generated").exists())

    def test_incomplete_generation_is_not_loaded(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(dataset, "run_sql") as sql:
            with self.assertRaisesRegex(RuntimeError, "generation is incomplete"):
                dataset.load(Path(directory))
            sql.assert_not_called()

    def test_repeated_load_is_rejected_before_inserting(self):
        with patch.object(dataset, "run_sql", return_value="7") as sql:
            with self.assertRaisesRegex(RuntimeError, "already contain data"):
                dataset.load(self.directory)
            self.assertEqual(1, sql.call_count)
            self.assertNotIn("INSERT", sql.call_args.args[0])

    def test_data_commands_cannot_overlap(self):
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory)
            with dataset.data_lock(destination):
                with self.assertRaisesRegex(RuntimeError, "Another lab data command"):
                    with dataset.data_lock(destination):
                        self.fail("A second command acquired the lock")
            with dataset.data_lock(destination):
                pass


if __name__ == "__main__":
    unittest.main()
