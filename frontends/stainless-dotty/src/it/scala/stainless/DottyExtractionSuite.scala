/* Copyright 2009-2017 EPFL, Lausanne */

package stainless

class DottyExtractionSuite extends ExtractionSuite {

  testExtractAll("verification/valid")
  testExtractAll("verification/invalid")
  testExtractAll("verification/unchecked")
  testExtractAll("imperative/valid")
  testExtractAll("imperative/invalid")
  testExtractAll("termination/valid")
  testExtractAll("termination/looping")
  testExtractAll("extraction/valid")
  testRejectAll("extraction/invalid")

}

