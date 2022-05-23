package com.eldrix.hermes.sct;

import java.time.LocalDate;

public interface IDescription {
    public long id();
    public LocalDate effectiveTime();
    public boolean active();
    public long moduleId();
    public long conceptId();
    public String languageCode();
    public long typeId();
    public String term();
    public long caseSignificanceId();
}