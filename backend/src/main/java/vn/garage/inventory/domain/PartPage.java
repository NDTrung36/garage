package vn.garage.inventory.domain;

import java.util.List;

public record PartPage(List<PartView> items, int page, int size, long totalElements, long totalPages) { }
