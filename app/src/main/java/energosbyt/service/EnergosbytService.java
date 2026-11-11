package energosbyt.service;

import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import energosbyt.dto.Installation;
import energosbyt.dto.Invoices;
import energosbyt.dto.Owner;
import energosbyt.dto.OwnerPatch;
import energosbyt.dto.Reading;
import energosbyt.dto.Readings;
import energosbyt.dto.Realty;
import energosbyt.dto.Tariff;
import energosbyt.exception.DomainException;
import energosbyt.repository.EnergosbytRepository;

@Service
public class EnergosbytService {
    private static final LocalDate MIN_DATE = LocalDate.of(1970, 1, 1);
    private static final LocalDate MAX_DATE = MIN_DATE.plusDays(0xffff);
    private static final long MAX_COUNTER = 0xffff_ffffL;

    private final EnergosbytRepository repository;
    private final Object[] writeLocks = new Object[64];

    public EnergosbytService(EnergosbytRepository repository) {
        this.repository = repository;
        Arrays.setAll(writeLocks, ignored -> new Object());
    }

    public Owner owner(String id) {
        return found(repository.owner(pathId(id)));
    }

    public void patchOwner(String id, OwnerPatch patch) {
        BigInteger ownerId = pathId(id);
        if (patch.fio() == null && patch.phone() == null) {
            throw DomainException.invalidInput("provide fio or phone");
        }
        if (patch.fio() != null && patch.fio().isBlank()) {
            throw DomainException.field("fio");
        }
        if (patch.phone() != null && patch.phone().isBlank()) {
            throw DomainException.field("phone");
        }

        synchronized (writeLock(ownerId)) {
            Owner current = found(repository.owner(ownerId));
            String fio = patch.fio() == null ? current.fio() : patch.fio();
            String phone = patch.phone() == null ? current.phone() : patch.phone();
            String previousPhone = phone.equals(current.phone()) ? current.phonePrev() : current.phone();
            var updated = new Owner(ownerId, fio, phone, previousPhone);
            if (!updated.equals(current)) {
                repository.updateOwner(updated);
            }
        }
    }

    public Realty realty(String id) {
        return found(repository.realty(pathId(id)));
    }

    public void postReading(String id, Reading reading) {
        BigInteger realty = pathId(id);
        LocalDate date = parseDate(reading.readingDate())
                .filter(value -> !value.isBefore(MIN_DATE) && !value.isAfter(MAX_DATE))
                .orElseThrow(() -> DomainException.field("reading_date"));
        if (!validId(reading.userId())) {
            throw DomainException.field("user_id");
        }
        if (!validId(reading.tariffId())) {
            throw DomainException.field("tariff_id");
        }
        if (!validId(reading.gaugeId())) {
            throw DomainException.field("gauge_id");
        }
        if (!validCounter(reading.dayKw())) {
            throw DomainException.field("day_kw");
        }
        if (!validCounter(reading.nightKw())) {
            throw DomainException.field("night_kw");
        }
        requireFound(repository.readingReferencesExist(realty, reading));

        synchronized (writeLock(realty)) {
            repository.recordReading(realty, date, reading);
        }
    }

    public Readings readings(String id, String dateFrom, String dateTo) {
        BigInteger realty = pathId(id);
        LocalDate from = dateParameter(dateFrom, "date_from");
        LocalDate to = dateParameter(dateTo, "date_to");
        if (from.isAfter(to)) {
            throw DomainException.parameter("date_to");
        }
        requireFound(repository.realtyExists(realty));

        LocalDate first = from.isBefore(MIN_DATE) ? MIN_DATE : from;
        LocalDate last = to.isAfter(MAX_DATE) ? MAX_DATE : to;
        List<Reading> readings = first.isAfter(last) ? List.of() : repository.readings(realty, first, last);
        return new Readings(readings, readings.size());
    }

    public Invoices invoices(String id, String limit, String offset) {
        BigInteger realty = pathId(id);
        BigInteger pageSize = limit == null ? BigInteger.TEN : unsigned(limit, "limit");
        BigInteger skip = offset == null ? BigInteger.ZERO : unsigned(offset, "offset");
        requireFound(repository.realtyExists(realty));

        long count = repository.invoiceCount(realty);
        if (pageSize.signum() == 0 || skip.compareTo(BigInteger.valueOf(count)) >= 0) {
            return new Invoices(List.of(), count);
        }
        long available = count - skip.longValueExact();
        long size = pageSize.min(BigInteger.valueOf(available)).longValueExact();
        return new Invoices(repository.invoices(realty, size, skip.longValueExact()), count);
    }

    public Tariff tariff(String date) {
        return found(repository.tariff(dateParameter(date, "date")));
    }

    public void installGauge(String id, Installation body) {
        BigInteger realty = pathId(id);
        if (!validId(body.gaugeId())) {
            throw DomainException.field("gauge_id");
        }
        requireFound(repository.realtyExists(realty) && repository.gaugeExists(body.gaugeId()));
        synchronized (writeLock(realty)) {
            repository.installGauge(realty, body.gaugeId());
        }
    }

    private Object writeLock(BigInteger id) {
        return writeLocks[Math.floorMod(id.hashCode(), writeLocks.length)];
    }

    private static BigInteger pathId(String value) {
        BigInteger id = unsigned(value, "id");
        if (id.signum() == 0) {
            throw DomainException.parameter("id");
        }
        return id;
    }

    private static BigInteger unsigned(String value, String parameter) {
        if (value.isEmpty() || !value.chars().allMatch(character -> character >= '0' && character <= '9')) {
            throw DomainException.parameter(parameter);
        }
        var number = new BigInteger(value);
        if (number.bitLength() > 64) {
            throw DomainException.parameter(parameter);
        }
        return number;
    }

    private static boolean validId(BigInteger id) {
        return id != null && id.signum() > 0 && id.bitLength() <= 64;
    }

    private static boolean validCounter(Long counter) {
        return counter != null && counter >= 0 && counter <= MAX_COUNTER;
    }

    private static Optional<LocalDate> parseDate(String value) {
        if (value == null || value.length() != 10) {
            return Optional.empty();
        }
        try {
            var date = LocalDate.parse(value);
            return date.toString().equals(value) ? Optional.of(date) : Optional.empty();
        } catch (DateTimeParseException error) {
            return Optional.empty();
        }
    }

    private static LocalDate dateParameter(String value, String name) {
        return parseDate(value).orElseThrow(() -> DomainException.parameter(name));
    }

    private static <T> T found(Optional<T> value) {
        return value.orElseThrow(DomainException::notFound);
    }

    private static void requireFound(boolean exists) {
        if (!exists) {
            throw DomainException.notFound();
        }
    }
}
