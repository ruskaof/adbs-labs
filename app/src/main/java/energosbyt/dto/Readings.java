package energosbyt.dto;

import java.util.List;

public record Readings(List<Reading> readings, int count) {}
