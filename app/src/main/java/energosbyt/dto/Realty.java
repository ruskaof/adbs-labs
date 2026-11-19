package energosbyt.dto;

import java.math.BigInteger;

public record Realty(BigInteger id, String address, long square, String type, String number, int rooms, int floor,
                  int porch) {}
