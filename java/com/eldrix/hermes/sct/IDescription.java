package com.eldrix.hermes.sct;

public interface IDescription extends IComponent {
    long conceptId();
    String languageCode();
    long typeId();
    String term();
    long caseSignificanceId();
}