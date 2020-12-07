(ns com.eldrix.hermes.service)

(defprotocol SnomedService
  (getConcept [svc concept-id])
  (getExtendedConcept [svc concept-id])
  (getDescriptions [svc concept-id])
  (getReferenceSets [svc component-id])
  (getComponentRefsetItems [svc component-id refset-id])
  (reverseMap [svc refset-id code])
  (getPreferredSynonym [svc concept-id langs])
  (subsumedBy? [svc concept-id subsumer-concept-id])
  (parseExpression [svc s])
  (search [svc params])
  (close [svc]))
