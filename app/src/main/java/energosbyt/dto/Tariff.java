package energosbyt.dto;

import java.math.BigInteger;

public record Tariff(BigInteger id, String name, long priceDayKwph, long priceNightKwph, String validFrom,
                  String validTo) {}
