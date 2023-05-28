package com.eldrix.hermes.sct;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An extended concept is a denormalised representation of a single concept bringing together all useful data into one
 * convenient structure, that can then be cached and used for inference.
 */
public interface IExtendedConcept extends IConcept {
    List<IDescription> descriptions();

    Map<Long, Set<Long>> parentRelationships();

    Map<Long, Set<Long>> directParentRelationships();

    List<IConcreteValue> concreteValues();

    Set<Long> refsets();
}
