(ns grafter.rdf.repository-test
  (:require [grafter.rdf.templater :refer [graph]]
            [clojure.java.io :refer [file] :as io]
            [grafter.rdf.protocols :as pr :refer [->Triple]]
            [grafter.rdf.repository :refer :all]
            [grafter.rdf :refer [statements]]
            [grafter.url :refer [->GrafterURL]]
            [grafter.rdf.formats :refer :all]
            [clojure.test :refer :all]
            [grafter.rdf.repository :as repo])
  (:import org.eclipse.rdf4j.model.impl.GraphImpl
           org.eclipse.rdf4j.sail.memory.MemoryStore
           org.eclipse.rdf4j.repository.sparql.SPARQLRepository
           java.net.URI
           java.net.URL))

(def quad-fixture-file-path (io/resource "grafter/rdf/rdf-types.trig"))

(def triple-fixture-file-path (io/resource "grafter/rdf/rdf-types.ttl"))

(deftest reading-writing-to-Graph
  (let [graph (GraphImpl.)
        g (URI. "http://foo")
        s (URI. "http://s")
        p (URI. "http://p")
        o (URI. "http://o")]
    (grafter.rdf/add-statement graph "http://foo" (pr/->Quad s p o nil))

    (is (= (pr/->Quad s p o g)
           (first (grafter.rdf/statements graph))))))

(deftest with-transaction-test
  (with-open [test-db (repo/->connection (repo))]
    (testing "Transactions return last result of form if there's no error."
      (is (= :return-value (with-transaction test-db
                             :return-value))))
    (testing "Adding values in a transaction are visible after the transaction commits."
      (with-transaction test-db
        (pr/add test-db (graph (URI. "http://example.org/test/graph")
                               [(URI. "http://test/subj") [(URI. "http://test/pred") (URI. "http://test/obj")]])))

      (is (query test-db "ASK WHERE { <http://test/subj> ?p ?o }")))))

(deftest sparql-repo-test
  (testing "Works with a query-url arg of type String"
    (let [repo (sparql-repo "http://localhost:3001/sparql/state")]
      (is (instance? SPARQLRepository repo))))

  (testing "Works with a query-url arg which satisfies the IURI Protocol"
    (let [repo (sparql-repo (->GrafterURL "http" "localhost" 3001 ["sparql" "state"] nil nil))]
      (is (instance? SPARQLRepository repo)))))


(defn load-rdf-types-data
  ([file]
   (let [db (repo)]
     (with-open [conn (->connection db)]
       (pr/add conn (statements file)))

     db)))

(deftest query-test
  (with-open [test-db (->connection (load-rdf-types-data triple-fixture-file-path))]
    (are [type f?]
        (is (f? (let [o (-> (query test-db (str "PREFIX : <http://example/> SELECT ?o WHERE {" type " :hasValue ?o . }"))
                            first
                            (get :o))]
                  o)))

      :integer integer?
      :string (fn [v] (= "hello" (str v)))
      :date (partial instance? java.util.Date)
      :decimal (partial instance? java.math.BigDecimal)
      :float float?
      :double (partial instance? java.lang.Double)
      :boolean (fn [v] (#{true false} v)))))

(deftest round-tripping-data-and-queries
  (testing "When loading triples from a file and round-tripping througb a SPARQL
  repo it should be identical to the one read in."

    (testing "roundtripping ttl file"
      (let [file triple-fixture-file-path]
        (with-open [conn (->connection (load-rdf-types-data file))]
          (is (= (set (statements conn))
                 (set (statements file)))))))

    (testing "roundtripping trig file"
      (let [file quad-fixture-file-path]
        (with-open [conn (->connection (load-rdf-types-data file))]
          (is (= (set (statements conn))
                 (set (statements file)))))))))

(deftest delete-statement-test
  (testing "arity 2 delete"
    (are [initial-data delete-form]
        (with-open [test-db (->connection (load-rdf-types-data initial-data))]
          (let [quads-to-delete (statements test-db)]
            delete-form
            (is (not (query test-db "ASK { ?s ?p ?o } LIMIT 1"))
                "Should be deleted")))

      triple-fixture-file-path (pr/delete test-db quads-to-delete)
      quad-fixture-file-path (pr/delete test-db quads-to-delete)))

  (testing "arity 3 delete"
    (let [repo (repo)]

      (with-open [conn (->connection repo)]
        (-> conn
            (pr/add
             (URL. "http://a")
             (statements triple-fixture-file-path))
            (pr/add
             (URL. "http://b")
             (statements triple-fixture-file-path))))

      (with-open [test-db (->connection repo)]
        (pr/delete test-db
                   (URL. "http://a")
                   (statements triple-fixture-file-path))
        (is (not (query test-db "ASK { GRAPH <http://a> { ?s ?p ?o } } LIMIT 1"))
            "Should be deleted")

        (is (query test-db "ASK { GRAPH <http://b> { ?s ?p ?o } } LIMIT 1")
            "Should not be deleted")))))

(deftest col-reduce-repo-test
  (is (= (into #{} (repo))
         #{}))

  (is (= (into #{} (repo (io/resource "grafter/rdf/1.nt")))
         #{(->Triple (URI. "http://one")
                     (URI. "http://lonely")
                     (URI. "http://triple"))})))

(deftest fixture-repo-test
  (is (= (into #{} (fixture-repo))
         #{}))

  (is (= (into #{} (fixture-repo (io/resource "grafter/rdf/1.nt")))
         #{(->Triple (URI. "http://one")
                     (URI. "http://lonely")
                     (URI. "http://triple"))}))

  (testing "Calling with multiple sets of quads appends them all into the repo"
    (with-open [conn (->connection (fixture-repo (io/resource "quads.nq")
                                                 (io/resource "quads.trig")))]
      (is (= 2 (count (grafter.rdf/statements conn)))))))

(deftest resource-repo-test
  (testing "Calling with multiple sets of quads appends them all into the repo"
    (with-open [conn (->connection (resource-repo "quads.nq"
                                                 "quads.trig"))]
      (is (= 2 (count (grafter.rdf/statements conn)))))))

(deftest sail-repo-test
  (is (instance? org.eclipse.rdf4j.repository.Repository (sail-repo)))
  (is (= (into #{} (sail-repo))
         #{})))


(deftest batched-query-test
  (let [repo (repo)]
    (with-open [conn (->connection repo)]
               (grafter.rdf/add conn
                                (grafter.rdf/statements (io/resource "grafter/rdf/triples.nt"))))

    (with-open [c (->connection repo)]
      (is (= 3 (count (batched-query "SELECT * WHERE { ?s ?p ?o .}"
                                     c
                                     1
                                     0)))))))
