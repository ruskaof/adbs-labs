"""Generate and manage the lab fixtures independently of the Java HTTP service."""

import argparse
import csv
import random
import subprocess
import sys
import time
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
START = date(2023, 11, 1)
END = date(2026, 10, 31)
DAYS = (END - START).days + 1
PROPERTIES = 1000
TABLES = {
    "dim_realty": "id,address,square,type,number,rooms,floor,porch,created_at",
    "dim_gauges": "id,name,country,created_at",
    "dim_tariffs": "id,name,price_day_kwph,price_night_kwph,valid_from,valid_to,created_at,updated_at",
    "dim_users": "id,fio,phone,phone_prev,created_at,updated_at",
    "fact_gauge_bindings": "gauge_id,realty_id,created_at,sign,version",
    "fact_meter_readings": "realty_id,user_id,tariff_id,gauge_id,reading_date,day_kw,night_kw,created_at",
    "fact_invoice": "realty_id,user_id,tariff_id,gauge_id,day_kw,night_kw,price,deadline,created_at",
}
COMPOSE = ["docker", "compose", "--env-file", str(ROOT / ".env.local")]


@dataclass(frozen=True)
class Tariff:
    start: date
    end: date
    day_price: int
    night_price: int

    def price(self, day_usage: int, night_usage: int) -> int:
        return day_usage * self.day_price + night_usage * self.night_price


TARIFFS = (
    Tariff(START, date(2025, 11, 1), 540, 270),
    Tariff(date(2025, 11, 1), date(2099, 1, 1), 620, 310),
)


def write_usage(readings, invoices, rng, realty, first_gauge, replacement, new_gauge):
    gauge = first_gauge
    day_counter = night_counter = month_day = month_night = 0
    for index in range(DAYS):
        current = START + timedelta(days=index)
        if current == replacement:
            gauge = new_gauge
            day_counter = night_counter = 0
        day_use, night_use = rng.randint(2, 15), rng.randint(1, 7)
        day_counter += day_use
        night_counter += night_use
        month_day += day_use
        month_night += night_use
        corrected = ((realty - 1) * DAYS + index) % 100 in (11, 47, 83)
        if corrected:
            readings.writerow((realty, realty, 1, gauge, current, day_counter + 1,
                               night_counter, f"{current} 18:00:00"))
        hour = "19:00:00" if corrected else "18:00:00"
        readings.writerow((realty, realty, 1, gauge, current, day_counter,
                           night_counter, f"{current} {hour}"))
        tomorrow = current + timedelta(days=1)
        if tomorrow.day == 1:
            tariff = next(t for t in TARIFFS if t.start <= current < t.end)
            invoices.writerow((realty, realty, 1, gauge, month_day, month_night,
                               tariff.price(month_day, month_night), tomorrow.replace(day=10),
                               f"{current} 23:59:59"))
            month_day = month_night = 0


def generate(directory: Path = DATA):
    directory.mkdir(parents=True, exist_ok=True)
    marker = directory / ".generated"
    marker.unlink(missing_ok=True)
    rng = random.Random(42)
    replacements = set(rng.sample(range(2, PROPERTIES + 1), PROPERTIES // 20))
    created = f"{START} 00:00:00"
    with ExitStack() as stack:
        writers = {
            table: csv.writer(stack.enter_context((directory / f"{table}.csv").open(
                "w", encoding="utf-8", newline="")), lineterminator="\n")
            for table in TABLES
        }
        for tariff in TARIFFS:
            valid_from, valid_to = f"{tariff.start} 00:00:00", f"{tariff.end} 00:00:00"
            writers["dim_tariffs"].writerow((1, "День–Ночь", tariff.day_price, tariff.night_price,
                                            valid_from, valid_to, valid_from, valid_from))
        writers["dim_gauges"].writerow((2, "Меркурий 206", "Россия", created))
        next_gauge = PROPERTIES + 2
        for realty in range(1, PROPERTIES + 1):
            house, unit = (realty - 1) // 100 + 1, (realty - 1) % 100
            flat = unit < 90
            writers["dim_realty"].writerow((
                realty, f"г. Москва, ул. Энергетиков, д. {house}", rng.randint(30, 110),
                "flat" if flat else "nrcp", unit + 1 if flat else "",
                rng.randint(1, 4) if flat else 0, unit // 10 + 1 if flat else 1,
                unit // 50 + 1, created))
            writers["dim_users"].writerow((realty, f"Собственник №{realty}",
                                          f"+7900{realty:07d}", "", created, created))
            gauge = 1 if realty == 1 else realty + 1
            writers["dim_gauges"].writerow((gauge, "Меркурий 206", "Россия", created))
            writers["fact_gauge_bindings"].writerow((gauge, realty, created, 1, 1))
            replacement = None
            if realty in replacements:
                replacement = date(rng.randint(2024, 2025), rng.randint(1, 12), 1)
                installed = f"{replacement} 00:00:00"
                writers["dim_gauges"].writerow((next_gauge, "Энергомера CE102", "Россия", installed))
                writers["fact_gauge_bindings"].writerow((gauge, realty, created, -1, 1))
                writers["fact_gauge_bindings"].writerow((next_gauge, realty, installed, 1, 2))
            write_usage(writers["fact_meter_readings"], writers["fact_invoice"], rng,
                        realty, gauge, replacement, next_gauge)
            if replacement is not None:
                next_gauge += 1
    marker.touch()


def run_sql(query: str, source: Path | None = None, timeout: float = 15) -> str:
    command = COMPOSE + [
        "exec", "-T", "clickhouse", "sh", "-c",
        'exec clickhouse-client --user "$CLICKHOUSE_USER" --password "$CLICKHOUSE_PASSWORD" '
        '--database "$CLICKHOUSE_DB" --multiquery --query "$1"', "sh", query,
    ]
    with ExitStack() as stack:
        stdin = stack.enter_context(source.open("rb")) if source is not None else subprocess.DEVNULL
        result = subprocess.run(command, cwd=ROOT, stdin=stdin, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout.decode("utf-8").strip()


def await_clickhouse():
    deadline = time.monotonic() + 45
    while True:
        try:
            run_sql("SELECT 1", timeout=2)
            return
        except (RuntimeError, subprocess.TimeoutExpired) as error:
            if time.monotonic() >= deadline:
                raise RuntimeError("ClickHouse is unavailable. Run make run first.") from error
            time.sleep(1)


def require_empty_tables():
    names = ", ".join(f"'{table}'" for table in TABLES)
    populated = run_sql(f"""
        SELECT count() FROM system.parts
        WHERE active AND rows > 0 AND database = currentDatabase() AND table IN ({names})
        """)
    if populated != "0":
        raise RuntimeError("Lab tables already contain data. Use make teardown and make setup to rebuild them.")


def load(directory: Path = DATA):
    if not (directory / ".generated").is_file():
        raise RuntimeError("CSV generation is incomplete. Run make gen first.")
    for table in TABLES:
        if not (directory / f"{table}.csv").is_file():
            raise RuntimeError(f"Missing {table}.csv. Run make gen first.")
    require_empty_tables()
    for table, columns in TABLES.items():
        run_sql(f"INSERT INTO {table} ({columns}) FORMAT CSV", directory / f"{table}.csv", timeout=180)
        print(f"Loaded {table}")


def setup(directory: Path = DATA):
    await_clickhouse()
    for statement in (ROOT / "sql" / "schema.sql").read_text(encoding="utf-8").split(";"):
        if statement.strip():
            run_sql(statement.strip())
    require_empty_tables()
    generate(directory)
    load(directory)


def teardown():
    for table in TABLES:
        run_sql(f"DROP TABLE IF EXISTS {table} SYNC")


@contextmanager
def data_lock(directory: Path):
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / ".lock").open("a+b") as lock:
        if lock.tell() == 0:
            lock.write(b"\0")
            lock.flush()
        lock.seek(0)
        if sys.platform == "win32":
            import msvcrt
            acquire = lambda: msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            release = lambda: msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            acquire = lambda: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            release = lambda: fcntl.flock(lock, fcntl.LOCK_UN)
        try:
            acquire()
        except OSError as error:
            raise RuntimeError("Another lab data command is running.") from error
        try:
            yield
        finally:
            release()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("gen", "load", "setup", "teardown"))
    args = parser.parse_args()
    try:
        with data_lock(DATA):
            {"gen": generate, "load": load, "setup": setup, "teardown": teardown}[args.command]()
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
