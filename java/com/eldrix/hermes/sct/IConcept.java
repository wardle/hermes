package com.eldrix.hermes.sct.IConcept;

import java.time.LocalDate;

public interface IConcept {
    public long id();
    public boolean active();
    public LocalDate effectiveTime();
    public long moduleId();
    public long definitionStatusId();
}