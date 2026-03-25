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
    private static final IFn componentRefsetItemsFn;
    private static final IFn componentRefsetIdsFn;
    private static final IFn refsetItemFn;
    private static final IFn refsetDescriptorAttributeIdsFn;
    private static final IFn extendedRefsetItemFn;
    private static final IFn componentRefsetItemsExtendedFn;
    private static final IFn installedReferenceSetsFn;
    private static final IFn refsetMembersFn;
    private static final IFn parseExpressionFn;
    private static final IFn validateExpressionFn;
    private static final IFn renderExpressionFn;
    private static final IFn renderExpressionStarFn;
    private static final IFn subsumesFn;

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
        componentRefsetItemsFn = Clojure.var("com.eldrix.hermes.core", "component-refset-items");
        componentRefsetIdsFn = Clojure.var("com.eldrix.hermes.core", "component-refset-ids");
        refsetItemFn = Clojure.var("com.eldrix.hermes.core", "refset-item");
        refsetDescriptorAttributeIdsFn = Clojure.var("com.eldrix.hermes.core", "refset-descriptor-attribute-ids");
        extendedRefsetItemFn = Clojure.var("com.eldrix.hermes.core", "extended-refset-item");
        componentRefsetItemsExtendedFn = Clojure.var("com.eldrix.hermes.core", "component-refset-items-extended");
        installedReferenceSetsFn = Clojure.var("com.eldrix.hermes.core", "installed-reference-sets");
        refsetMembersFn = Clojure.var("com.eldrix.hermes.core", "refset-members");
        parseExpressionFn = Clojure.var("com.eldrix.hermes.core", "parse-expression");
        validateExpressionFn = Clojure.var("com.eldrix.hermes.core", "validate-expression");
        renderExpressionFn = Clojure.var("com.eldrix.hermes.core", "render-expression");
        renderExpressionStarFn = Clojure.var("com.eldrix.hermes.core", "render-expression*");
        subsumesFn = Clojure.var("com.eldrix.hermes.core", "subsumes");
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

    private Hermes(String path, String defaultLocale) {
        HashMap<Object, Object> params = new HashMap<>();
        if (path == null) {
            throw new NullPointerException("Failed to open hermes: invalid path");
        }
        if (defaultLocale != null) {
            params.put("default-locale", defaultLocale);
        }
        _hermes = openFn.invoke(path, intoFn.invoke(emptyMap, params));
    }

    /**
     * Open a hermes SNOMED terminology server at the path specified.
     *
     * @param path - path to database directory
     */
    private Hermes(String path) {
        this(path, null);
    }

    /**
     * Open a hermes service from the local filesystem at the path specified.
     * The system default locale will be used as a fallback locale.
     * @param path - path on local filesystem to data files
     * @return - a hermes service
     */
    public static Hermes openLocal(String path) {
        return new Hermes(path);
    }

    /**
     * Open a hermes service from the local filesystem at the path specified.
     * @param path - path on local filesystem to data files
     * @param defaultLocale - fallback locale in the format of Accept-Language HTTP header
     * @return - a hermes service
     */
    public static Hermes openLocal(String path, String defaultLocale) {
        return new Hermes(path, defaultLocale);
    }

    /**
     * Open a hermes service using an already established service
     * @param svc - hermes service
     * @return - a hermes service
     */
    public static Hermes open(Object svc) {
        return new Hermes(svc);
    }

    /**
     * Close the hermes service.
     */
    public void close() {
        closeFn.invoke(_hermes);
    }

    /**
     * Returns a list language reference set ids representing the database
     * default fallback language.
     * @return a list of language reference set identifiers
     */
    @SuppressWarnings("unchecked")
    public List<Long> matchLocale() {
        return (List<Long>) matchLocaleFn.invoke(_hermes);
    }

    /**
     * Return a list of language reference set ids for the language range
     * @param languageRange - language Range defined in RFC 4647
     * @return a list of language reference set identifiers
     */
    @SuppressWarnings("unchecked")
    public List<Long> matchLocale(String languageRange) {
        return (List<Long>) matchLocaleFn.invoke(_hermes, languageRange);
    }

    /**
     * Perform a search for descriptions.
     * @param request - an initialised SearchRequest
     * @return results of the search
     */
    public List<IResult> search(SearchRequest request) {
        @SuppressWarnings("unchecked")
        List<IResult> results = (List<IResult>) searchFn.invoke(_hermes, request._params);
        if (results != null ) {
            return Collections.unmodifiableList(results);
        }
        return Collections.emptyList(); // unlike clojure, java cannot treat null as an empty list
    }

    /**
     * Return the concept with the specified identifier
     * @param conceptId - concept identifier
     * @return concept
     */
    public IConcept concept(long conceptId) {
        return (IConcept) conceptFn.invoke(_hermes, conceptId);
    }

    /**
     * Return the concepts with the specified identifiers
     * @param conceptIds
     * @return concepts
     */
    public List<IConcept> concepts(List<Long> conceptIds) {
        return conceptIds.stream().map(this::concept).collect(Collectors.toList());
    }

    /**
     * Return a denormalized 'extended' version of the concept specified.
     * @param conceptId
     * @return IExtendedConcept - a denormalised 'extended' version of a concept
     */
    public IExtendedConcept extendedConcept(long conceptId) {
        return (IExtendedConcept) extendedConceptFn.invoke(_hermes, conceptId);
    }

    /**
     * Return the preferred synonym for the concept specified using the
     * language preference specified.
     * @param conceptId
     * @param languageTag
     * @return IDescription representing the preferred synonym
     */
    public IDescription preferredSynonym(long conceptId, String languageTag) {
        return (IDescription) preferredSynonymFn.invoke(_hermes, conceptId, languageTag);
    }

    /**
     * Return the preferred synonym for the concept specified using the system
     * default locale.
     * @param conceptId - concept identifier
     * @return IDescription representing the preferred synonym
     */
    public IDescription preferredSynonym(long conceptId) {
        return preferredSynonym(conceptId, Locale.getDefault().toLanguageTag());
    }

    /**
     * Return the preferred term for the concept specified in the default system
     * locale.
     * @param conceptId - concept identifier
     * @return - the preferred term for the concept
     */
    public String preferredTerm(long conceptId) {
        return preferredSynonym(conceptId).term();
    }

    /**
     * Return a lower-case version of the preferred term specified.
     * @param conceptId - concept identifier
     * @return the preferred term
     */
    public String lowerCasePreferredTerm(long conceptId) {
        return (String) lowerCaseTermFn.invoke(preferredSynonym(conceptId));
    }

    /**
     * Return the synonyms of the concept specified.
     * @param conceptId - concept identifier
     * @return a list of synonyms
     */
    public List<String> synonyms(long conceptId) {
        @SuppressWarnings("unchecked")
        List<IDescription> descriptions = (List<IDescription>) synonymsFn.invoke(_hermes, conceptId);
        return descriptions.stream().map(IDescription::term).collect(Collectors.toList());
    }

    /**
     * Return a list of synonyms for the concept specified, returning only
     * those either preferred or acceptable in the given language reference sets
     * @param conceptId - concept id
     * @param langRefsetIds - a collection of language reference set ids
     * @return a list of synonyms
     */
    public List<String> synonyms(long conceptId, Collection<Long> langRefsetIds) {
        @SuppressWarnings("unchecked")
        List<IDescription> descriptions = (List<IDescription>) synonymsFn.invoke(_hermes, conceptId, langRefsetIds);
        return descriptions.stream().map(IDescription::term).collect(Collectors.toList());
    }

    /**
     * Is the concept a type of the parent concept?
     * @param concept - the concept to be tested
     * @param parent - the parent concept
     * @return boolean
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

    /**
     * Return a sequence of reference set items for the given component.
     * @param componentId - component identifier (e.g., concept id)
     * @return a list of reference set items
     */
    @SuppressWarnings("unchecked")
    public List<IRefsetItem> componentRefsetItems(long componentId) {
        List<IRefsetItem> results = (List<IRefsetItem>) componentRefsetItemsFn.invoke(_hermes, componentId);
        return results != null ? Collections.unmodifiableList(results) : Collections.emptyList();
    }

    /**
     * Return a sequence of reference set items for the given component,
     * filtered to a specific reference set.
     * @param componentId - component identifier
     * @param refsetId - reference set identifier
     * @return a list of reference set items
     */
    @SuppressWarnings("unchecked")
    public List<IRefsetItem> componentRefsetItems(long componentId, long refsetId) {
        List<IRefsetItem> results = (List<IRefsetItem>) componentRefsetItemsFn.invoke(_hermes, componentId, refsetId);
        return results != null ? Collections.unmodifiableList(results) : Collections.emptyList();
    }

    /**
     * Return a collection of reference set identifiers to which this component
     * is a member.
     * @param componentId - component identifier
     * @return a collection of reference set identifiers
     */
    @SuppressWarnings("unchecked")
    public Collection<Long> componentRefsetIds(long componentId) {
        Collection<Long> results = (Collection<Long>) componentRefsetIdsFn.invoke(_hermes, componentId);
        return results != null ? results : Collections.emptySet();
    }

    /**
     * Return a specific reference set item by its UUID.
     * @param uuid - the UUID of the reference set item
     * @return the reference set item, or null if not found
     */
    public IRefsetItem refsetItem(UUID uuid) {
        return (IRefsetItem) refsetItemFn.invoke(_hermes, uuid);
    }

    /**
     * Return a vector of attribute description concept ids for the given
     * reference set, as defined by the reference set descriptor.
     * @param refsetId - reference set identifier
     * @return a list of attribute description concept ids
     */
    @SuppressWarnings("unchecked")
    public List<Long> refsetDescriptorAttributeIds(long refsetId) {
        List<Long> results = (List<Long>) refsetDescriptorAttributeIdsFn.invoke(_hermes, refsetId);
        return results != null ? results : Collections.emptyList();
    }

    /**
     * Return an extended version of the reference set item, supplemented with
     * a map of extended attributes as defined by the reference set descriptor.
     * @param item - a reference set item
     * @return the extended reference set item
     */
    public IRefsetItem extendedRefsetItem(IRefsetItem item) {
        return (IRefsetItem) extendedRefsetItemFn.invoke(_hermes, item);
    }

    /**
     * Return a sequence of reference set items for the given component,
     * supplemented with extended attributes as defined by the reference set
     * descriptor.
     * @param componentId - component identifier
     * @return a list of extended reference set items
     */
    @SuppressWarnings("unchecked")
    public List<IRefsetItem> componentRefsetItemsExtended(long componentId) {
        List<IRefsetItem> results = (List<IRefsetItem>) componentRefsetItemsExtendedFn.invoke(_hermes, componentId);
        return results != null ? Collections.unmodifiableList(results) : Collections.emptyList();
    }

    /**
     * Return a sequence of reference set items for the given component,
     * filtered to a specific reference set, supplemented with extended
     * attributes as defined by the reference set descriptor.
     * @param componentId - component identifier
     * @param refsetId - reference set identifier
     * @return a list of extended reference set items
     */
    @SuppressWarnings("unchecked")
    public List<IRefsetItem> componentRefsetItemsExtended(long componentId, long refsetId) {
        List<IRefsetItem> results = (List<IRefsetItem>) componentRefsetItemsExtendedFn.invoke(_hermes, componentId, refsetId);
        return results != null ? Collections.unmodifiableList(results) : Collections.emptyList();
    }

    /**
     * Return a set of identifiers representing installed reference sets.
     * Unlike simply using the SNOMED ontology to find all reference sets, this
     * only returns reference sets with at least one installed member item.
     * @return a set of reference set identifiers
     */
    @SuppressWarnings("unchecked")
    public Set<Long> installedReferenceSets() {
        Set<Long> results = (Set<Long>) installedReferenceSetsFn.invoke(_hermes);
        return results != null ? results : Collections.emptySet();
    }

    /**
     * Return a set of concept identifiers for the members of the given
     * reference set.
     * @param refsetId - reference set identifier
     * @return a set of member concept identifiers
     */
    @SuppressWarnings("unchecked")
    public Set<Long> refsetMembers(long refsetId) {
        Set<Long> results = (Set<Long>) refsetMembersFn.invoke(_hermes, refsetId);
        return results != null ? results : Collections.emptySet();
    }

    // ---- Compositional grammar / expressions ----

    private static final class ExpressionImpl implements IExpression {
        final Object _expression;
        ExpressionImpl(Object expression) {
            _expression = expression;
        }
        @Override
        public String toString() {
            return (String) renderExpressionStarFn.invoke(_expression);
        }
    }

    /**
     * Parse a SNOMED CT compositional grammar expression string.
     * @param s - a compositional grammar expression string
     * @return the parsed expression
     */
    public IExpression parseExpression(String s) {
        return new ExpressionImpl(parseExpressionFn.invoke(_hermes, s));
    }

    /**
     * Validate a SNOMED CT expression. Returns true if valid.
     * Both syntactic/structural checks and MRCM constraint checks are
     * performed.
     * @param expression - a parsed expression
     * @return true if the expression is valid
     */
    public boolean validateExpression(IExpression expression) {
        return validateExpressionFn.invoke(_hermes, ((ExpressionImpl) expression)._expression) == null;
    }

    /**
     * Validate a concept as an expression.
     * @param conceptId - concept identifier
     * @return true if valid
     */
    public boolean validateExpression(long conceptId) {
        return validateExpressionFn.invoke(_hermes, conceptId) == null;
    }

    /**
     * Render a SNOMED CT expression to a compositional grammar string with
     * preferred synonyms for the default locale.
     * @param expression - a parsed expression
     * @return the rendered expression string
     */
    public String renderExpression(IExpression expression) {
        return (String) renderExpressionFn.invoke(_hermes, ((ExpressionImpl) expression)._expression);
    }

    /**
     * Render a SNOMED CT expression to a compositional grammar string with
     * preferred synonyms for the specified language.
     * @param expression - a parsed expression
     * @param languageRange - Accept-Language header value (e.g. "en-GB")
     * @return the rendered expression string
     */
    public String renderExpression(IExpression expression, String languageRange) {
        return (String) renderExpressionFn.invoke(_hermes, ((ExpressionImpl) expression)._expression, languageRange);
    }

    /**
     * Render a concept to a compositional grammar string with preferred
     * synonyms for the default locale.
     * @param conceptId - concept identifier
     * @return the rendered expression string
     */
    public String renderExpression(long conceptId) {
        return (String) renderExpressionFn.invoke(_hermes, conceptId);
    }

    /**
     * Render a concept to a compositional grammar string with preferred
     * synonyms for the specified language.
     * @param conceptId - concept identifier
     * @param languageRange - Accept-Language header value (e.g. "en-GB")
     * @return the rendered expression string
     */
    public String renderExpression(long conceptId, String languageRange) {
        return (String) renderExpressionFn.invoke(_hermes, conceptId, languageRange);
    }

    /**
     * Test subsumption between two expressions using structural subsumption.
     * @param a - the first expression
     * @param b - the second expression
     * @return the subsumption result
     */
    public SubsumptionResult subsumes(IExpression a, IExpression b) {
        Keyword result = (Keyword) subsumesFn.invoke(_hermes, ((ExpressionImpl) a)._expression, ((ExpressionImpl) b)._expression);
        return SubsumptionResult.fromKeyword(result.getName());
    }

    /**
     * Test subsumption between two expressions using the specified mode.
     * @param a - the first expression
     * @param b - the second expression
     * @param mode - subsumption mode (structural or OWL)
     * @return the subsumption result
     * @throws RuntimeException if mode is OWL and the reasoner is unavailable
     */
    public SubsumptionResult subsumes(IExpression a, IExpression b, SubsumptionMode mode) {
        Keyword result = (Keyword) subsumesFn.invoke(_hermes, ((ExpressionImpl) a)._expression, ((ExpressionImpl) b)._expression,
                keyword("mode"), keyword(mode.keyword()));
        return SubsumptionResult.fromKeyword(result.getName());
    }

    /**
     * Test subsumption between two concepts using structural subsumption.
     * @param conceptIdA - the first concept identifier
     * @param conceptIdB - the second concept identifier
     * @return the subsumption result
     */
    public SubsumptionResult subsumes(long conceptIdA, long conceptIdB) {
        Keyword result = (Keyword) subsumesFn.invoke(_hermes, conceptIdA, conceptIdB);
        return SubsumptionResult.fromKeyword(result.getName());
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
        String _accept_language;
        Collection<Long> _language_refset_ids;
        int _fuzzy = -1;
        int _fallback_fuzzy = -1;
        Boolean _show_fsn;
        String _constraint;
        Collection<Long> _is_a = null;
        Object _s_kw = keyword("s");
        Object _max_hits_kw = keyword("max-hits");
        Object _accept_language_kw = keyword("accept-language");
        Object _language_refset_ids_kw = keyword("language-refset-ids");
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
        public SearchRequestBuilder setAcceptLanguage(String acceptLanguage) {
            _accept_language = acceptLanguage;
            return this;
        }
        public SearchRequestBuilder setLanguageRefsetIds(Collection<Long> refsetIds) {
            _language_refset_ids = refsetIds;
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
            if (_accept_language != null) {
                params.put(_accept_language_kw, _accept_language);
            }
            if (_language_refset_ids != null) {
                params.put(_language_refset_ids_kw, _language_refset_ids);
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

    /**
     * Result of a subsumption test between two expressions, aligned with FHIR
     * $subsumes operation outcomes.
     */
    public enum SubsumptionResult {
        EQUIVALENT("equivalent"),
        SUBSUMES("subsumes"),
        SUBSUMED_BY("subsumed-by"),
        NOT_SUBSUMED("not-subsumed");

        private final String keyword;

        SubsumptionResult(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }

        public static SubsumptionResult fromKeyword(String name) {
            for (SubsumptionResult r : values()) {
                if (r.keyword.equals(name)) {
                    return r;
                }
            }
            throw new IllegalArgumentException("Unknown subsumption result: " + name);
        }
    }

    /**
     * Mode of subsumption testing between expressions.
     */
    public enum SubsumptionMode {
        STRUCTURAL("structural"),
        OWL("owl");

        private final String keyword;

        SubsumptionMode(String keyword) {
            this.keyword = keyword;
        }

        public String keyword() {
            return keyword;
        }
    }

}
