(ns sqlosure.galaxy
  (:require [active.clojure
             [condition :as c]
             [record :refer [define-record-type]]]
            [sqlosure
             [relational-algebra :as rel]
             [sql :as sql]
             [type :as t]
             [utils :as u]]))

(define-record-type tuple
  ^{:doc "A tuple holds a (sorted) vector of values."}
  (make-tuple expressions) tuple?
  [^{:doc "A (sorted) vector of values."}
   expressions tuple-expressions])

(define-record-type db-galaxy
  ^{:doc "A galaxy is the Clojure representation of a record-type in a
(relational) database. Galaxies serve as the interface which can be queried just
as a SQL-table as created by `sqlosure.core/table`."}
  (really-make-db-galaxy name type setup-fn query) db-galaxy?
  [^{:doc "The name of the galaxy."} name db-galaxy-name
   ^{:doc "The type that this galaxy represents."} type db-galaxy-type
   ^{:doc "Takes a db-connection, sets up virgin DB tables."}
   ^{:doc "A function to set up a database table. This function will be called
by `initialize-db-galaxies!`."}
   setup-fn db-galaxy-setup-fn
   ^{:doc "The base query to access the underlying data (regularly, this would
be a `sqlosure.relatinal-algebra/base-relation`."}
   query db-galaxy-query])

(def ^:dynamic *db-galaxies*
  "`*db-galaxies*` is a map wrapped in an atom that contains all known
  galaxies as a mapping of galaxy-name ->
  `sqlosure.realional-algebra/base-relation`."
  (atom {}))

(defn make&install-db-galaxy
  "`make&install-db-galaxy` takes a `name` for a new galaxy, a `type` that
  this galaxy represents, the `setup-fn` function to create the corresponding
  db tables and a `query` (?).
  It returns a `sqlosure.relational-algebra/base-relation` for this new table
  and registers the galaxy to `*db-galaxies*`."
  [name type setup-fn query]
  (let [dg (really-make-db-galaxy name type setup-fn query)
        rel (rel/make-base-relation name
                                    (rel/alist->rel-scheme {name type})
                                    :universe sql/sql-universe
                                    :handle dg)]
    (swap! *db-galaxies* assoc name rel)
    rel))

(defn initialize-db-galaxies!
  "Takes a connection and installs all galaxies currently stored in
  `*db-galaxies*` to the database."
  [conn]
  (doall (map (fn [[name glxy]]
                ((db-galaxy-setup-fn (rel/base-relation-handle glxy)) conn))
              @*db-galaxies*)))

(define-record-type db-type-data
  ^{:doc "`db-type-data` is a container for the necessary functions to work with
arbitrary data-types as db-types."}
  (make-db-type-data scheme reifier value->db-expression-fn)
  db-type-data?
  [^{:doc "A `sqlosure.relational-algebra/rel-scheme`."}
   scheme db-type-data-scheme
   ^{:doc "A function that takes the result of a query to the underlying table
and transforms it into it's data-representation (for example, a db-record to a
Clojure record, etc.)."}
   reifier db-type-data-reifier
   ^{:doc "A function that takes the data-representation of the value and
returns a db-representation of the value (for example, a Clojure record to a
`sqlosure.galaxy/tuple`)."}
   value->db-expression-fn db-type-data-value->db-expression-fn])

(defn make-db-type
  "`make-db-type` creates a new `sqlosure.type/base-type` for a db-type in a to
  be used in a galaxy. The newly created data type will be registered to the
  sql-universe.

  * `name`: the name of the type
  * `pred`:  a predicate function for values of that type
  * `const->datum-fn`
  * `datum->const-fn`
  * `scheme`: a `sqlosure.relational-algebra/rel-scheme` for this new type
  * `reifier`: a function that knows how to reconstruct a value of this type
               from a query result
  * `value->db-expression-fn`: a function that knows how to create a db-entry
                               from a value of this type"
  [name pred const->datum-fn datum->const-fn scheme reifier
   value->db-expression-fn
   & {:keys [ordered? numeric?]
      :or [ordered? false numeric? false]}]
  (t/make-base-type name pred const->datum-fn datum->const-fn
                    :universe sql/sql-universe
                    :galaxy-type? true
                    :data
                    (make-db-type-data scheme reifier value->db-expression-fn)))

(define-record-type db-operator-data
  ^{:doc "Used to define the `sqlosure.relational-algebra/rator-data` component
of an operator. This enables you to define your own functions for arbitrary
(db-)types."}
  (make-db-operator-data base-query transformer-fn) db-operator-data?
  [^{:doc "A query to lift another value to this context (for underlying values,
especially components of a more complex product-type."}
   base-query db-operator-data-base-query
   ^{:doc "A fuction that takes a result and extracs/transforms it the way this
operator is intended to work."}
   transformer-fn db-operator-data-transformer-fn])

(defn- make-name-generator
  "Takes a `prefix` (String) and returns a function that returns the prefix with
  a \"_n\"-suffix, where n is an integer starting with 0 that gets incremented
  upon each subsequent call."
  [prefix]
  (let [count (atom 0)]
    (fn []
      (let [c @count]
        (swap! count inc)
        (str prefix "_" c)))))

(defn- list->product
  "Takes a list and returns a `sqlosure.relational-algebra/product` for this
  list."
  [ql]
  (if (empty? ql)
    rel/the-empty
    (rel/make-product (first ql)
                      (list->product (rest ql)))))

(defn- apply-restrictions
  "Takes a list of restrictions `rl` and a query and applies the restrictions to
  the query."
  [rl q]
  (cond
    (nil? q) rel/the-empty
    (not (rel/query? q)) (c/assertion-violation `apply-restrictions
                                                "not a query" q)
    :else
    (if (empty? rl)
      q
      (apply-restrictions (rest rl)
                          (rel/make-restrict (first rl) q)))))

(defn restrict-to-scheme
  "Takes a rel-scheme `scheme` and a query `q` and returns a new
  `sqlosure.relational-algebra/project` which wraps the old query in a
  projection with the mappings of `scheme`."
  [scheme q]
  (when (empty? scheme)
    (c/assertion-violation `restrict-to-scheme "empty scheme"))
  (when-not (rel/query? q)
    (c/assertion-violation `restrict-to-scheme "unknown query" q))
  (rel/make-project
   (map (fn [k]
          [k (rel/make-attribute-ref k)])
        (rel/rel-scheme-columns scheme))
   q))

(defn make-new-names
  "Takes a string `base` and a list `lis` and returns a list of
  '(\"base_0\", ..., \"base_n\") where `n` = `(count lis)`."
  [base lis]
  (when-not (empty? lis)
    (let [gen (make-name-generator base)]
      (take (count lis) (repeatedly gen)))))

(declare dbize-project dbize-expression)

;; TODO restrict-outer
;; FIXME write tests for queries + environments!
(defn- dbize-query*
  "Returns new query, environment mapping names to tuples."
  [q generate-name]
  (letfn
      [(worker [q generate-name]
         (cond
           (rel/empty-query? q) [rel/the-empty-rel-scheme {}]
           (rel/base-relation? q)
           (let [handle (rel/base-relation-handle q)]
             ;; If the query is a galaxy, we need to extract the underlying
             ;; query and relation.
             (if (db-galaxy? handle)
               (let [db-query (db-galaxy-query handle)
                     name (db-galaxy-name handle)
                     cols (rel/rel-scheme-columns (rel/query-scheme db-query))
                     new-names (make-new-names name cols)]
                 ;; What's goining on here?
                 ;; We basically split up the value reference into 'real' cols
                 ;; and assign new names to it.
                 [(rel/make-project (map
                                     (fn [k new-name]
                                       [new-name (rel/make-attribute-ref k)])
                                     cols new-names)
                                    db-query)
                  ;; We keep a reference of the name to a tuple containing the
                  ;; 'real' column names to replace them when necessary.
                  {name
                   (make-tuple (mapv rel/make-attribute-ref new-names))}])
               [q {}]))  ;; Nothing to do here, just keep the old query.
           (rel/project? q)
           (let [[alist underlying env]
                 (dbize-project (rel/project-alist q)
                                (rel/project-query q)
                                generate-name)]
             [(rel/make-project alist underlying) env])
           (rel/restrict? q)
           (let [[underlying env] (worker (rel/restrict-query q) generate-name)
                 [exp queries restrictions]
                 (dbize-expression (rel/restrict-exp q) env
                                   (rel/restrict-query q)
                                   generate-name)]
             [(restrict-to-scheme (rel/query-scheme underlying)
                                  (apply-restrictions
                                   (cons exp restrictions)
                                   (list->product (cons underlying queries))))
              env])
           ;; (rel/restrict-outer? q) ...
           (rel/combine? q)
           (let [[dq1 env1] (worker (rel/combine-query-1 q) generate-name)
                 [dq2 env2] (worker (rel/combine-query-2 q) generate-name)]
             [(rel/make-combine (rel/combine-rel-op q) dq1 dq2)
              (merge env1 env2)])
           (rel/order? q)
           (let [[underlying env] (worker (rel/order-query q) generate-name)]
             [(rel/make-order
               (map (fn [[k v]]
                      (let [[exp queries restrictions]
                            (dbize-expression k env (rel/order-query q)
                                              generate-name)]
                        (if (or (seq queries)
                                (seq restrictions))
                          (c/assertion-violation
                           `dbize-query "object values used in order query")
                          ;; NOTE This implicitly turns a map into an alist.
                          ;;      This is not the only playe, but Is this okay
                          ;;      here?
                          [exp v])))
                    (rel/order-alist q))
               underlying)
              env])
           (rel/top? q)
           (let [[underlying env] (worker (rel/top-query q) generate-name)]
             [(rel/make-top (rel/top-offset q) (rel/top-count q) underlying)
              env])
           :else (c/assertion-violation `dbize-query "unknown query" q)))]
    (worker q generate-name)))

(defn dbize-query
  "Takes a sqlosure query and returns a 'flattened' representation of the same
  query."
  [q]
  (dbize-query* q (make-name-generator "dbize")))

(defn dbize-project
  [alist q-underlying generate-name]
  (let [[underlying env] (dbize-query* q-underlying generate-name)]
    ;; NOTE is it wise to loop through a map (may be unsorted)?
    (loop [alist alist
           names []
           bindings env
           queries '()
           restrictions '()]
      (if (empty? alist)
        [names
         (apply-restrictions restrictions
                             (list->product (cons underlying queries)))
         bindings]
        (let [[name value] (first alist)
              [exp more-queries more-restrictions]
              (dbize-expression value env q-underlying generate-name)]
          (if (tuple? exp)
            (let [exprs (tuple-expressions exp)
                  new-names (make-new-names name exprs)]
              (recur (rest alist)
                     (concat names (map (fn [cexp new-name]
                                          [new-name cexp])
                                        exprs new-names))
                     (assoc
                      bindings
                      name
                      (make-tuple (mapv rel/make-attribute-ref new-names)))
                     (concat more-queries queries)
                     (concat more-restrictions restrictions)))
            (recur (rest alist)
                   (conj names [name exp])
                   bindings ;; queries restrictions
                   (concat more-queries queries)
                   (concat more-restrictions restrictions))))))))

(defn- take+drop
  "Takes an integer `n` and a list and returns a vector with
  `[first n elems, remainder]`."
  [n lis]
  [(take n lis) (drop n lis)])

(defn reify-query-result
  "`reify-query-result` takes one result-record of a query and the (non-dbized)
  scheme of the query ran and applies the necessary reification to the resulting
  values."
  [res scheme & [opts]]
  (let [cs (rel/rel-scheme-columns scheme)]
    (loop [cols (rel/rel-scheme-columns scheme)
           res res
           rev '()]
      (if (empty? cols)
        (if (:as-maps opts)
          (into {} (u/zip cs (reverse rev)))
          (into [] (reverse rev)))
        (let [typ (get (rel/rel-scheme-map scheme) (first cols))]
          (if (and (satisfies? t/base-type-protocol typ)
                   (db-type-data? (t/-data typ)))
            (let [data (t/-data typ)
                  scheme (db-type-data-scheme data)
                  reifier (db-type-data-reifier data)
                  [prefix suffix] (take+drop
                                   (count (rel/rel-scheme-columns scheme)) res)]
              (recur (rest cols)
                     suffix
                     (cons (reifier prefix) rev)))
            (recur (rest cols)
                   (rest res)
                   (cons (first res) rev))))))))

(defn rename-query
  "Takes a query `q` and a name-generator function `generate-name` and returns
  the query wrapped in a `sqlosure.relational-algebra/project` with mappings
  from a newly generated name to the old reference."
  [q generate-name]
  (when-not (fn? generate-name)
    (c/assertion-violation `rename-query
                           "generate-name is not a function"
                           generate-name))
  (when-not (rel/query? q)
    (c/assertion-violation `rename-query
                           "unknown query" q))
  (let [cols (rel/rel-scheme-columns (rel/query-scheme q))
        names (map (fn [_] (generate-name)) cols)]
    [(map rel/make-attribute-ref names)
     (rel/make-project (map (fn [name k]
                              [name (rel/make-attribute-ref k)])
                            names cols)
                       q)]))

(defn dbize-expression
  "Returns dbized expression, list of renamed additional queries, and list of
  restrictions on the resulting product."
  [e env underlying generate-name]
  (let [base-queries (atom '())
        restrictions (atom '())
        underlying-scheme (rel/query-scheme underlying)]
    (letfn
        [(worker [e]
           (cond
             (rel/attribute-ref? e)
             (or (get env (rel/attribute-ref-name e)) e)
             (rel/const? e)
             (let [typ (rel/const-type e)]
               (if (and (satisfies? t/base-type-protocol typ)
                        (db-type-data? (t/-data typ)))
                 ((db-type-data-value->db-expression-fn (t/-data typ))
                  (rel/const-val e))
                 e))
             (rel/const-null? e)
             (let [typ (rel/null-type e)]
               (if (and (satisfies? t/base-type-protocol typ)
                        (db-type-data? (t/-data typ)))
                 ((db-type-data-value->db-expression-fn (t/-data typ))
                  (rel/const-val e))
                 e))
             (rel/application? e)
             (let [rator (rel/application-rator e)
                   data (rel/rator-data rator)
                   base-query (when (db-operator-data? data)
                                (db-operator-data-base-query data))
                   rands (rel/application-rands e)
                   initiate
                   (fn []
                     (apply (db-operator-data-transformer-fn data)
                            (concat
                             (map worker rands)
                             (map
                              (fn [rand]
                                (rel/expression-type
                                 (rel/rel-scheme->environment underlying-scheme)
                                 rand))
                              rands))))]
               (if (db-operator-data? data)
                 ;; FIXME this case is not clear (example necessary?).
                 (if base-query
                   (let [[restriction-fn transform] (initiate)
                         base-query-refs
                         (if base-query
                           (let [[base-query-refs renamed-base-query]
                                 (rename-query base-query generate-name)]
                             (reset! base-queries (cons renamed-base-query
                                                        @base-queries))
                             base-query-refs)
                           '())]
                     (when restriction-fn
                       (reset! restrictions
                               (cons (apply restriction-fn base-query-refs)
                                     @restrictions)))
                     (apply transform base-query-refs))
                   (initiate))
                 ;; Base case, nothing special here.
                 (apply rel/make-application
                        rator
                        (map worker rands))))
             (tuple? e)
             (make-tuple (mapv worker (tuple-expressions e)))
             (rel/aggregation? e)
             (let [expr (worker (rel/aggregation-expr e))]
               (if (= :count (rel/aggregation-operator e))
                 (rel/make-aggregation
                  :count
                  (loop [expr expr]
                    (if (tuple? expr)
                      ;; doesn't matter
                      (recur (first (tuple-expressions expr)))
                      expr)))
                 (rel/make-aggregation (rel/aggregation-operator e)
                                       expr)))
             ;; TODO aggregation*
             (rel/aggregation*? e)
             (do (println "NOT YET IMPLEMENTED")
                 (c/assertion-violation `dbize-expression "NOT YET IMPLEMENTED"
                                        e))
             (rel/case-expr? e)
             ;; FIXME: this is incomplete: we should commute this
             ;; with tuples inside the case if the result type is a
             ;; DB type
             ;; FIXME Tests!
             (rel/make-case-expr (map (fn [[k v]]
                                        [(worker k) (worker v)])
                                      (rel/case-expr-alist e))
                                 (worker (rel/case-expr-default e)))
             :else (c/assertion-violation `dbize-expression
                                          "unknown expression" e)))]
      (let [dbized (worker e)]
        [dbized @base-queries @restrictions]))))
