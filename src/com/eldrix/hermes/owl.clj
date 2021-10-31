(ns com.eldrix.hermes.owl
  "Support for ontological reasoning.
   
   The SNOMED International SNOMED owl toolkit is available at:
   https://github.com/IHTSDO/snomed-owl-toolkit
   
   This builds an ontology from SNOMED CT using:
   * the OWL axiom reference set
   * fully specified names as rdfs:label
   * preferred synonyms as skos:prefLabel
   * other synonyms as skos:altLabel
   * text definitions as skos:definition
   
   ")



(comment
  (require '[org.semanticweb.owlapi])
  (def input-manager (org.semanticweb.owlapi.apibinding.OWLManager.))
  (.loadOntologyFromOntologyDocument input-manager)
  input-manager
  )