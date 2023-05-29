package com.eldrix.hermes.sct;

import java.time.LocalDate;

public interface IModuleDependencyRefsetItem extends IRefsetItem {
    LocalDate sourceEffectiveTime();

    LocalDate targetEffectiveTime();
}
