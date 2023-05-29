package com.eldrix.hermes.sct;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

public interface IRefsetItem {
    UUID id();

    LocalDate effectiveTime();

    boolean active();

    long moduleId();

    long refsetId();

    long referencedComponentId();

    Collection<Object> fields();
}
