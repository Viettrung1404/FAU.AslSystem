package com.asl.backend.catalog.domain;
import java.io.Serializable;
import java.util.UUID;
public record UnitReference(UUID id, String title, int position) implements Serializable { }
