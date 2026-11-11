package energosbyt.dto;

import java.util.List;

public record Invoices(List<Invoice> invoices, long count) {}
