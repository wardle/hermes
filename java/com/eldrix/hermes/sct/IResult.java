package com.eldrix.hermes.sct;

public interface IResult {
    /**
     * The Description identifier
     * @return
     */
    long id();

    /**
     * The concept identifier for this Description
     * @return
     */
    long conceptId();

    /**
     * The cached 'term' of this Description.
     * @return
     */
    String term();

    /**
     * The system-wide cached 'preferred term' for this Description
     * determined at the time of index creation. It is provided
     * for convenience only for common use-cases. It should be ignored
     * if there are different runtime language preferences.
     * @return
     */
    String preferredTerm();
}
