(ns sqlosure.query-comprehension-test
  (:require [sqlosure.query-comprehension :refer :all]
            [sqlosure.relational-algebra :refer :all]
            [sqlosure.relational-algebra-sql :refer :all]
            [sqlosure.database :refer :all]
            [sqlosure.db-postgresql :refer :all]
            [sqlosure.type :refer :all]
            [sqlosure.universe :refer :all]
            [sqlosure.sql :refer :all]
            [sqlosure.sql-put :as put]
            [active.clojure.monad :refer :all]
            [clojure.test :refer :all]
            [sqlosure.optimization :as opt]))

(def test-universe (make-universe))

(def tbl1 (make-sql-table "tbl1"
                          (make-rel-scheme {"one" string%
                                            "two" integer%})
                          :universe test-universe))

(def tbl2 (make-sql-table "tbl2"
                          (make-rel-scheme {"three" blob%
                                            "four" string%})
                          :universe test-universe))

(defn put-query [query]
  (put/sql-select->string
   put/default-sql-put-parameterization
   (query->sql query)))

(let [movies-table (make-sql-table 'movies
                                       (make-rel-scheme {"title" string%
                                                         "director" string%
                                                         "year" integer%
                                                         "any_good" boolean%})
                                       :universe (make-universe)
                                       :handle "movies")]
  (put-query (get-query (monadic [movies (embed movies-table)]
                                 (project {"title" (! movies "title")
                                           "year" (! movies "year")})
                                 (project {"title" (! movies "title")})))))

(deftest const-restrict-test
  (is (= '("SELECT two AS foo FROM tbl1 WHERE (one = ?)" "foobar")
         (sqlosure.sql-put/sql-select->string
          sqlosure.sql-put/default-sql-put-parameterization
          (query->sql (opt/optimize-query
                       (get-query (monadic
                                   [t1 (embed tbl1)]
                                   (restrict (=$ (! t1 "one")
                                                 (make-const string% "foobar")))
                                   (project {"foo" (! t1 "two")})))))))))

(deftest trivial
  (is (rel-scheme=?
       (make-rel-scheme {"foo" integer%})
       (query-scheme
        (get-query (monadic
                    [t1 (embed tbl1)]
                    [t2 (embed tbl2)]
                    (restrict (=$ (! t1 "one")
                                  (! t2 "four")))
                    (project {"foo" (! t1 "two")})))))))


(deftest combine
  (is (rel-scheme=?
       (make-rel-scheme {"foo" string%})
       (query-scheme
        (get-query
         (monadic
          [t1 (embed tbl1)]
          [t2 (embed tbl2)]
          (union (project {"foo" (! t1 "one")})
                 (project {"foo" (! t2 "four")})))))))

  (is (rel-scheme=?
       (make-rel-scheme {"foo" string%})
       (query-scheme
        (get-query
         (monadic
          [t1 (embed tbl1)]
          [t2 (embed tbl2)]
          (subtract (project {"foo" (! t1 "one")})
                    (project {"foo" (! t2 "four")})))))))

  (is (rel-scheme=?
       (make-rel-scheme {"bar" blob%})
       (query-scheme
        (get-query
         (monadic
          [t1 (embed tbl1)
           t2 (embed tbl2)]
          (divide (project {"foo" (! t1 "one")
                            "bar" (! t2 "three")})
                  (project {"foo" (! t2 "four")}))))))))

(deftest order-t
  (is (rel-scheme=?
       (make-rel-scheme {"foo" integer%})
       (query-scheme
        (get-query
         (monadic
          [t1 (embed tbl1)
           t2 (embed tbl2)]
          (restrict (=$ (! t1 "one")
                        (! t2 "four")))
          (order {(! t1 "one") :ascending})
          (project {"foo" (! t1 "two")})))))))

(deftest xy-test-1
  (is (= '("SELECT three_1 AS res FROM (SELECT three AS three_1, four AS four_1 FROM tbl2), (SELECT one AS one_0, two AS two_0 FROM tbl1 WHERE (? = one)) WHERE (four_1 = one_0)" "foobar")
         (sqlosure.sql-put/sql-select->string
          sqlosure.sql-put/default-sql-put-parameterization
          (query->sql (opt/optimize-query
                       (get-query (monadic
                                   [t1 (embed tbl1)]
                                   (restrict (=$ (make-const string% "foobar")
                                                 (! t1 "one")))
                                   [t2 (embed tbl2)]
                                   (restrict (=$ (! t2 "four") (! t1 "one")))
                                   (project {"res" (! t2 "three")})))))))))