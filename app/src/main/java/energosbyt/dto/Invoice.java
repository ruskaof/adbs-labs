package energosbyt.dto;

import java.math.BigInteger;

public record Invoice(BigInteger tariffId, BigInteger gaugeId, long dayKw, long nightKw, BigInteger price,
                   String deadline, String createdAt) {}
