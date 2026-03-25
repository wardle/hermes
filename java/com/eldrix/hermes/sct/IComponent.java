package com.eldrix.hermes.sct;

import java.time.LocalDate;

public interface IComponent {
    long id();
    boolean active();
    LocalDate effectiveTime();
    long moduleId();
}

