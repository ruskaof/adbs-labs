package energosbyt.dto;

import java.math.BigInteger;

public record Reading(String readingDate, BigInteger userId, BigInteger tariffId, BigInteger gaugeId,
                   Long dayKw, Long nightKw) {}
