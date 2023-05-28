package com.eldrix.hermes.client;

import java.util.*;
import java.util.stream.Collectors;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.Keyword;

import com.eldrix.hermes.sct.*;

/**
 * Hermes provides a thin java API around the hermes Clojure library.
 */
public final class Hermes {

    private final Object _hermes;
    private static final IFn openFn;
    private static final IFn closeFn;
    private static final IFn intoFn;
    private static final Object emptyMap;
    private static final IFn matchLocaleFn;
    private static final IFn searchFn;
    private static final IFn conceptFn;
    private static final IFn extendedConceptFn;
    private static final IFn preferredSynonymFn;
    private static final IFn subsumedByFn;
    private static final IFn parentRelationshipsOfTypeFn;
    private static final IFn childRelationshipsOfTypeFn;
    private static final IFn allChildrenFn;
    private static final IFn allParentsFn;
    private static final IFn lowerCaseTermFn;
    private static final IFn synonymsFn;
    private static final IFn areAnyFn;
    private static final IFn expandEcl;
    private static final IFn expandEclHistoric;
    private static final IFn expandEclPreferred;
    private static final IFn intersectEcl;
    private static final IFn isValidEcl;

    static {
        IFn require = Clojure.var("clojure.core", "require");
        openFn = Clojure.var("com.eldrix.hermes.core", "open");
        closeFn = Clojure.var("com.eldrix.hermes.core", "close");
        intoFn = Clojure.var("clojure.core", "into");
        emptyMap = Clojure.read("{}");

        require.invoke(Clojure.read("com.eldrix.hermes.core"));
        matchLocaleFn = Clojure.var("com.eldrix.hermes.core", "match-locale");
        searchFn = Clojure.var("com.eldrix.hermes.core", "search");
        conceptFn = Clojure.var("com.eldrix.hermes.core", "concept");
        extendedConceptFn = Clojure.var("com.eldrix.hermes.core", "extended-concept");
        preferredSynonymFn = Clojure.var("com.eldrix.hermes.core", "preferred-synonym");
        subsumedByFn = Clojure.var("com.eldrix.hermes.core", "subsumed-by?");
        parentRelationshipsOfTypeFn = Clojure.var("com.eldrix.hermes.core", "parent-relationships-of-type");
        childRelationshipsOfTypeFn = Clojure.var("com.eldrix.hermes.core", "child-relationships-of-type");
        allChildrenFn = Clojure.var("com.eldrix.hermes.core", "all-children");
        allParentsFn = Clojure.var("com.eldrix.hermes.core", "all-parents");
        lowerCaseTermFn = Clojure.var("com.eldrix.hermes.snomed", "term->lowercase");
        synonymsFn = Clojure.var("com.eldrix.hermes.core", "synonyms");
        areAnyFn = Clojure.var("com.eldrix.hermes.core", "are-any?");
        expandEcl = Clojure.var("com.eldrix.hermes.core", "expand-ecl");
        expandEclHistoric = Clojure.var("com.eldrix.hermes.core", "expand-ecl-historic");
        expandEclPreferred = Clojure.var("com.eldrix.hermes.core", "expand-ecl*");
        intersectEcl = Clojure.var("com.eldrix.hermes.core", "intersect-ecl");
        isValidEcl = Clojure.var("com.eldrix.hermes.core", "valid-ecl?");
    }

    private static Keyword keyword(String s) {
        return Keyword.intern(s);
    }

    private Hermes(Object hermes) {
        if (hermes == null) {
            throw new NullPointerException("No Hermes service available");
        }
        _hermes = hermes;
    }

    /**
     * Open a hermes SNOMED terminology server at the path specified.
     *
     * @param path - path to database directory
     */
    private Hermes(String path) {
        if (path == null) {
            throw new NullPointerException("Failed to open hermes: invalid path");
        }
        _hermes = openFn.invoke(path);
    }

    /**
     * Open a Hermes service from the local filesystem at the path specified
     * @param path
     * @return
     */
    public static Hermes openLocal(String path) {
        return new Hermes(path);
    }

    /**
     * Open a Hermes service using an already established service
     * @param svc
     * @return
     */
    public static Hermes open(Object svc) {
        return new Hermes(svc);
    }

    /**
     * Close the Hermes service.
     */
    public void close() {
        closeFn.invoke(_hermes);
    }

    @SuppressWarnings("unchecked")
    public List<Long> matchLocale(String languageRange) {
        return (List<Long>) matchLocaleFn.invoke(_hermes, languageRange);
    }

    /**
     * Perform a search for descriptions.
     * @param request
     * @return
     */
    public List<IResult> search(SearchRequest request) {
        @SuppressWarnings("unchecked")
        List<IResult> results = (List<IResult>) searchFn.invoke(_hermes, request._params);
        if (results != null ) {
            return Collections.unmodifiableList(results);
        }
        return Collections.emptyList(); // unlike clojure, java cannot treat null as an empty list
    }

    public IConcept concept(long conceptId) {
        return (IConcept) conceptFn.invoke(_hermes, conceptId);
    }

    public List<IConcept> concepts(List<Long> conceptIds) {
        return conceptIds.stream().map(this::concept).collect(Collectors.toList());
    }

    /**
     * Return a denormalized 'extended' version of the concept specified.
     * @param conceptId
     * @return
     */
    public IExtendedConcept extendedConcept(long conceptId) {
        return (IExtendedConcept) extendedConceptFn.invoke(_hermes, conceptId);
    }

    /**
     * Return the preferred synonym for the concept specified using the
     * language preference specified.
     * @param conceptId
     * @param languageTag
     * @return
     */
    public IDescription preferredSynonym(long conceptId, String languageTag) {
        return (IDescription) preferredSynonymFn.invoke(_hermes, conceptId, languageTag);
    }

    /**
     * Return the preferred synonym for the concept specified using the system
     * default locale.
     * @param conceptId
     * @return
     */
    public IDescription preferredSynonym(long conceptId) {
        return preferredSynonym(conceptId, Locale.getDefault().toLanguageTag());
    }

    public String preferredTerm(long conceptId) {
        return (String) preferredSynonym(conceptId).term();
    }

    /**
     * Return a lower-case version of the preferred term specified.
     * @param conceptId
     * @return
     */
    public String lowerCasePreferredTerm(long conceptId) {
        return (String) lowerCaseTermFn.invoke(preferredSynonym(conceptId));
    }

    /**
     * Return the synonyms of the concept specified.
     * @param conceptId
     * @return
     */
    public List<String> synonyms(long conceptId) {
        @SuppressWarnings("unchecked")
        List<IDescription> IDescriptions = (List<IDescription>) synonymsFn.invoke(_hermes, conceptId);
        return IDescriptions.stream().map(d -> {return (String) d.term();}).collect(Collectors.toList());
    }

    /**
     * Is the concept a type of the parent concept?
     * @param concept
     * @param parent
     * @return
     */
    public boolean isAConcept(IConcept concept, IConcept parent) {
        return (boolean) subsumedByFn.invoke(_hermes, concept.id(), parent.id());
    }

    /**
     * Is the concept subsumed by the concept specified?
     * @param conceptId
     * @param subsumerConceptId
     * @return
     */
    public boolean subsumedBy(long conceptId, long subsumerConceptId) {
        return (boolean) subsumedByFn.invoke(_hermes, conceptId, subsumerConceptId);
    }

    @SuppressWarnings("unchecked")
    public Collection<Long> childrenOfType(long conceptId, long typeConceptId) {
        return (Collection<Long>) childRelationshipsOfTypeFn.invoke(_hermes, conceptId, typeConceptId);
    }

    @SuppressWarnings("unchecked")
    public Collection<Long> parentsOfType(long conceptId, long typeConceptId) {
        return (Collection<Long>) parentRelationshipsOfTypeFn.invoke(_hermes, conceptId, typeConceptId);
    }

    /**
     * Returns concept identifiers for the transitive parent concepts of the
     * specified concept. By design, but perhaps unintuitively, this includes
     * the source concept identifier.
     * @param conceptId
     * @return
     */
    @SuppressWarnings("unchecked")
    public Collection<Long> allParents(long conceptId) {
        return (Collection<Long>) allParentsFn.invoke(_hermes, conceptId);
    }

    /**
     * Returns concept identifiers for the transitive child concepts of the
     * specified concept. By design, but perhaps unintuitively, this includes
     * the source concept identifier.
     * @param conceptId
     * @return
     */
    @SuppressWarnings("unchecked")
    public Collection<Long> allChildren(long conceptId) {
        return (Collection<Long>) allChildrenFn.invoke(_hermes, conceptId);
    }

    /**
     * Return concept identifiers for the transitive child concepts of the
     * concept for the type specified. By design, but perhaps unintuitively,
     * this includes the source concept identifier.
     * @param conceptId
     * @param typeConceptId
     * @return
     */
    @SuppressWarnings("unchecked")
    public Collection<Long> allChildren(long conceptId, long typeConceptId) {
        return (Collection<Long>) allChildrenFn.invoke(_hermes, conceptId, typeConceptId);
    }

    /**
     * Are any of the concept identifiers a type of one of the parent concept
     * identifiers?
     * @param conceptIds
     * @param parentConceptIds
     * @return
     */
    public boolean areAny(List<Long> conceptIds, Collection<Long> parentConceptIds) {
        // as areAnyFn returns a clojure truthy value or false, we convert to boolean by
        // testing against null
        return null != areAnyFn.invoke(_hermes, conceptIds, parentConceptIds);
    }

    public boolean isAny(Long conceptId, Collection<Long> parentConceptIds) {
        return areAny(Collections.singletonList(conceptId), parentConceptIds);
    }

    @SuppressWarnings("unchecked")
    public List<IResult> expandEcl(String ecl, boolean includeHistoric) {
        if (includeHistoric) {
            return (List<IResult>) expandEclHistoric.invoke(_hermes, ecl);
        }
        return (List<IResult>) expandEcl.invoke(_hermes, ecl);
    }

    @SuppressWarnings("unchecked")
    public List<IResult> expandEcl(String ecl, int maxHits) {
        return (List<IResult>) expandEcl.invoke(_hermes, ecl, maxHits);
    }

    /**
     * Expand ECL, returning a collection of results containing only preferred
     * synonyms from the given language preferences. This means there may be
     * more than one result per concept depending on language preferences. If
     * different behaviour is needed, then manually use {@link #matchLocale(String)}
     * and use the first reference set id in a call to {@link #expandEclPreferred(String, Long)}
     * @param ecl
     * @param languageRange
     * @return
     */
    @SuppressWarnings("unchecked")
    public List<IResult> expandEclPreferred(String ecl, String languageRange) {
        List<Long> refsetIds = matchLocale(languageRange);
        return (List<IResult>) expandEclPreferred.invoke(_hermes, ecl, refsetIds);
    }
    /**
     * Expand ECL, returning a collection of results containing only preferred
     * synonyms from the given language reference set. Results returned will include
     * a 'system' defined preferredTerm, cached at the time of index creation, which
     * should generally be ignored when using this method, and the 'term'
     * which will reflect the preferred term for the requested language.
     * @param ecl
     * @param languageRefsetId - identifier of the required language reference set
     * @return
     */
    @SuppressWarnings("unchecked")
    public List<IResult> expandEclPreferred(String ecl, Long languageRefsetId) {
        return (List<IResult>) expandEclPreferred.invoke(_hermes, ecl, Collections.singletonList(languageRefsetId));
    }

    /**
     * Expand ECL, returning a collection of results containing only preferred
     * synonyms from the given language preferences. This means there may be
     * more than one result per concept depending on language preferences.
     * @param ecl
     * @param languageRefsetIds - a collection of language reference set ids
     * @return
     */
    @SuppressWarnings("unchecked")
    public List<IResult> expandEclPreferred(String ecl, Collection<Long> languageRefsetIds) {
        return (List<IResult>) expandEclPreferred.invoke(_hermes, ecl, languageRefsetIds);
    }

    @SuppressWarnings("unchecked")
    public Set<Long> intersectEcl(Collection<Long> conceptIds, String ecl) {
        return (Set<Long>) intersectEcl.invoke(_hermes, conceptIds, ecl);
    }

    public boolean isValidEcl(String ecl) {
        return (boolean) isValidEcl.invoke(ecl);
    }

    public static class SearchRequest {

        final Object _params;

        private SearchRequest(Map<Object, Object> params) {
            _params = intoFn.invoke(emptyMap, params);
        }
    }

    public final static class SearchRequestBuilder {
        String _s;
        int _max_hits = -1;
        int _fuzzy = -1;
        int _fallback_fuzzy = -1;
        Boolean _show_fsn;
        String _constraint;
        Collection<Long> _is_a = null;
        Object _s_kw = keyword("s");
        Object _max_hits_kw = keyword("max-hits");
        Object _fuzzy_kw = keyword("fuzzy");
        Object _fallback_fuzzy_kw = keyword("fallback-fuzzy");
        Object _show_fsn_kw = keyword("show-fsn");
        Object _constraint_kw = keyword("constraint");
        Object _properties_kw = keyword("properties"); /// { :properties {snomed/IsA [14679004]}}

        public SearchRequestBuilder setS(String s) {
            _s = s;
            return this;
        }

        public SearchRequestBuilder setMaxHits(int max_hits) {
            _max_hits = max_hits;
            return this;
        }

        public SearchRequestBuilder setFuzzy(int fuzzy) {
            _fuzzy = fuzzy;
            return this;
        }

        public SearchRequestBuilder setFallbackFuzzy(int fallbackFuzzy) {
            _fallback_fuzzy = fallbackFuzzy;
            return this;
        }

        public SearchRequestBuilder setShowFsn(boolean show) {
            _show_fsn = show;
            return this;
        }

        public SearchRequestBuilder setConstraint(String ecl) {
            _constraint = ecl;
            return this;
        }

        public SearchRequestBuilder setIsA(Collection<Long> conceptIds) {
            _is_a = conceptIds;
            return this;
        }
        public SearchRequestBuilder setIsA(Long conceptId) {
            _is_a = Collections.singletonList( conceptId);
            return this;
        }

        public SearchRequest build() {
            HashMap<Object, Object> params = new HashMap<>();
            if (_s != null) {
                params.put(_s_kw, _s);
            }
            if (_max_hits >= 0) {
                params.put(_max_hits_kw, _max_hits);
            }
            if (_fuzzy >= 0) {
                params.put(_fuzzy_kw, _fuzzy);
            }
            if (_fallback_fuzzy >= 0) {
                params.put(_fallback_fuzzy_kw, _fallback_fuzzy);
            }
            if (_show_fsn != null) {
                params.put(_show_fsn_kw, _show_fsn);
            }
            if (_constraint != null) {
                params.put(_constraint_kw, _constraint);
            }
            if (_is_a != null) {
                HashMap<Object, Object> props = new HashMap<>();
                props.put(116680003L, _is_a);
                params.put(_properties_kw, props);
            }
            return new SearchRequest(params);
        }
    }

    public static SearchRequestBuilder newSearchRequestBuilder() {
        return new SearchRequestBuilder();
    }

}
