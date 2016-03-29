(ns sqlosure.db-connection
  (:require [active.clojure
             [condition :as c]
             [record :refer [define-record-type]]]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [sqlosure
             [jdbc-utils :as jdbc-utils]
             [optimization :as o]
             [relational-algebra :as rel]
             [relational-algebra-sql :as rsql]
             [sql :as sql]
             [sql-put :as put]
             [time :as time]
             [type :as t]]))

(define-record-type type-converter
  ^{:doc "`type-converter` serves as a container for the conversion functions
          between Clojure and DB types."}
  (make-type-converter value->db-value db-value->value) type-converter?
  [^{:doc "A function that takes a `sqlosure.type` type and a value and returns
           a corresponding value of a type the DB understands."}
   value->db-value type-converter-value->db-value
   ^{:doc "Inverse function to `value->db-value."}
   db-value->value type-converter-db-value->value])

(def sqlite3-type-converter
  (make-type-converter
   (fn [typ value]
     (cond
       (= typ t/boolean%) (if value 1 0)
       (= typ t/date%) (time/to-sql-time-string value)
       (= typ t/timestamp%) (time/to-sql-time-string value)
       :else value))
   (fn [typ value]
     (cond
       (= typ t/boolean%) (not= value 0)
       (= typ t/date%) (time/from-sql-time-string value)
       (= typ t/timestamp%) (time/from-sql-timestamp-string value)
       :else value))))

(def postgresql-type-converter
  (make-type-converter
   (fn [typ value]
      (case typ
        t/date% (time/to-sql-date value)
        t/timestamp% (time/to-sql-timestamp value)
        value))
   (fn [typ value]
     (case typ
       t/date% (time/from-sql-date value)
       t/timestamp% (time/from-sql-timestamp value)
       value))))

(define-record-type
  ^{:doc "`db-connection` serves as a container for storing the current
          db-connection as well as backend specific conversion and printer
          functions."}
  db-connection
  (make-db-connection conn parameterization type-converter) db-connection?
  [^{:doc "The database connection map as used by jdbc."}
   conn db-connection-conn
   ^{:doc "A function to print values in a way the dbms understands."}
   parameterization db-connection-paramaterization
   ^{:doc "A `db-connection/type-converter` record for conversion between
           Clojure and db types."}
   type-converter db-connection-type-converter])

(defn- sqlite3-put-combine
  "sqlite3 specific printer for combine queries."
  [param op left right]
  (print "SELECT * FROM (")
  (put/put-sql-select param left)
  (print (case op
           :union " UNION "
           :intersection " INTERSECT "
           :difference " EXCEPT "))
  (put/put-sql-select param right)
  (print ")"))

(defn- sqlite3-put-literal
  "sqlite3 specific printer for literals."
  [type val]
  (if (or (= true val) (= false val))
    (do (if val (print 1) (print 0))
        [])
    (put/default-put-literal type val)))

(def sqlite3-sql-put-parameterization
  "Printer for sqliter3."
  (put/make-sql-put-parameterization put/default-put-alias sqlite3-put-combine sqlite3-put-literal))

(def postgresql-sql-put-parameterization
  (put/make-sql-put-parameterization put/put-dummy-alias put/default-put-combine put/default-put-literal))

(defn run-query
  "Takes a database connection and a query and runs it against the database.
  The optional keyword arguments specify how to
  construct the result set:
    :as-arrays? - return each row as a vector of the field values, default false, in which
      a row is represented as a hash-map of the columns of the query scheme to the corresponding
      field values.
    :row-fn - applied to each row (vector or map) as the result set is constructed, defaults
      to identity.
    :result-set-fn - applied to a lazy sequence of all rows, defaults doall. Note that the
      function must realize the sequence, as the connection to the database may be closed after
      run-query returns.
    "
  [conn q & {:keys [optimize?] :or {optimize? true} :as opts-map}]
  (let [qq (if optimize? (o/optimize-query q) q)
        c (db-connection-type-converter conn)]
    (jdbc-utils/query (db-connection-conn conn) (rsql/query->sql qq)
                      (rel/query-scheme qq)
                      (type-converter-db-value->value c)
                      (type-converter-value->db-value c)
                      (db-connection-paramaterization conn)
                      (dissoc opts-map :optimize?))))

(defn- validate-scheme
  "`validate-scheme` takes two rel-schemes and checks if they obey the following
  rules:
      - `scheme` must not contain keys not present in `full-scheme`
      - `scheme` must contain all non-nullable fields present in `full-scheme`
      - `scheme`'s vals for each key must not differ in type from those in
        `full-scheme`"
  [full-scheme scheme]
  (letfn [(no-extra-keys [ks1 ks2]
            (empty? (set/difference (set ks2) (set ks1))))
          (no-type-difference [m1 m2]
            (reduce
             (fn [acc [k v]] (and acc (= v (get m1 k)))) true m2))
          (no-missing-non-nullable-types [m1 m2]
            (reduce
             (fn [acc [k v]]
               (if (not (t/-nullable? v))
                 (and acc (get m2 k))
                 acc)) true m1))]
    (let [[full-columns columns] [(rel/rel-scheme-columns full-scheme)
                                  (rel/rel-scheme-columns scheme)]
          [full-m m] [(rel/rel-scheme-map full-scheme) (rel/rel-scheme-map scheme)]
          extra (no-extra-keys full-columns columns)
          missing (no-missing-non-nullable-types full-m m)
          type-diff (no-type-difference full-m m)]
      (cond
        (not extra)
        (c/assertion-violation
         `validate-scheme
         "scheme contains extra keys not present in relation"
         (clojure.data/diff full-columns columns))
        (not missing)
        (c/assertion-violation
         `validate-scheme
         "scheme is missing non-nullable keys"
         (clojure.data/diff full-m m))
        (not type-diff)
        (c/assertion-violation
         `validate-scheme
         "scheme contains values that do not match types with relation"
         (clojure.data/diff full-m m))
        :else true))))

(defn insert!
  "`insert!` takes a db-connection and an sql-table and some rest `args` and
  attempts to insert them into the connected databases table.
  The table to insert into is the `relational-algebra/base-relation-handle` of
  `sql-table`.

  `args` can either be:
      - a set of values to insert if there is a value for each column. Those
        must be in the order of the columns in the table.
      - a rel-scheme, specifying the columns to insert the values into follwed
        by the desired values.

  If the rel-scheme of the value to insert is explicitly specified it will be
  checked for:
      - missing mandatory keys (non-nullable fields)
      - keys that are not present in the original scheme
      - type mismatches
  If this fails, an assertion will be thrown."
  [conn sql-table & args]
  (let [[scheme vals] (if (and (seq args) (rel/rel-scheme? (first args)))
                        [(first args) (rest args)]
                        [(rel/query-scheme sql-table) args])
        c (db-connection-type-converter conn)]
    (when (validate-scheme (rel/base-relation-scheme sql-table)
                           scheme)
      (jdbc/insert!
       (db-connection-conn conn)
       (sql/sql-table-name (rel/base-relation-handle sql-table))
       (into {}
             (map (fn [[k t] v]
                    [k ((type-converter-value->db-value c) t v)])
                  (rel/rel-scheme-map scheme)
                  vals))))))

(defn delete!
  [conn sql-table criterion-proc]
  (let [name (sql/sql-table-name (rel/base-relation-handle sql-table))]
    (jdbc/delete! (db-connection-conn conn)
                  name
                  (put/sql-expression->string
                   (db-connection-paramaterization conn)
                   (apply criterion-proc
                          (map rel/make-attribute-ref
                               (rel/rel-scheme-columns (rel/query-scheme sql-table))))))))

(defn update!
  [conn sql-table criterion-proc alist-first & args]
  (let [name (sql/sql-table-name (rel/base-relation-handle sql-table))
        scheme (rel/query-scheme sql-table)
        attr-exprs (map rel/make-attribute-ref
                        (rel/rel-scheme-columns scheme))
        alist (into {}
                    (map (fn [[k v]]
                           [k (rsql/expression->sql v)])
                         (if (fn? alist-first)
                           (apply alist-first attr-exprs)
                           (cons alist-first args))))]
    (jdbc/update! (db-connection-conn conn)
                  name
                  (into {} (map (fn [[k v]] [k (:val v)]) alist))
                  (put/sql-expression->string
                   (db-connection-paramaterization conn)
                   (rsql/expression->sql (apply criterion-proc attr-exprs))))))

(defn run-sql
  [conn sql]
  (jdbc/execute! (db-connection-conn conn) sql))
