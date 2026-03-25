package com.eldrix.hermes.sct;

public interface IComplexMapRefsetItem extends IRefsetItem {
    long mapGroup();

    long mapPriority();

    String mapRule();

    String mapAdvice();

    String mapTarget();

    long correlationId();

}
