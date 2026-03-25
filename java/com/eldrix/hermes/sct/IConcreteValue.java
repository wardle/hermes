package com.eldrix.hermes.sct;

public interface IConcreteValue extends IComponent {
    long sourceId();
    String value();
    long relationshipGroup();
    long typeId();
    long characteristicTypeId();
    long modifierId();
}
