package com.pa.sagememory.dto;

import java.util.List;

/** Evidence in retrieval rank order, without a generated answer. */
public record SearchResponse(List<EvidenceCard> data) {
}
