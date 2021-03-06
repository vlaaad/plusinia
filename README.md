# Plusinia

Solution to [N+1 problem](https://secure.phabricator.com/book/phabcontrib/article/n_plus_one/)
in [Lacinia](https://github.com/walmartlabs/lacinia).

# Rationale

Lacinia is a GraphQL query execution engine that calls resolvers individually for every 
entity we want to load more data about. This results in a so called N+1 problem, where 
fetching requested data requires executing 1 query to fetch N items, and then N queries
to fetch field information for every item. 

This problem can be solved with data loader library like 
[Superlifter](https://github.com/oliyh/superlifter). Superlifter can batch data loading 
requests across all executing queries by using a combination of time windows and batch sizes.
This results in a good overall performance at the expense of slightly increased query 
execution times due to time windows where execution engine waits for a batch to fill.

Plusinia provides an alternative solution for a use case where individual query execution 
time is more important than overall server performance.

# Installation

Use git dependency:

`io.github.vlaaad/plusinia {:git/tag "v1.23" :git/sha "24e34cd"}`

# Getting started

Plusinia works by wrapping Lacinia schema before it's compiled. It sets **all** query and 
object resolvers. Thus, it needs fetchers provided for every query and object field. The main
difference between Lacinia resolver and Plusinia fetcher is:
- Lacinia resolver transforms input value to result;
- Plusinia fetcher transforms a set of input values to map from input value to result.

Let's start with creating a schema:
```clojure
(require '[io.github.vlaaad.plusinia :as p]
         '[com.walmartlabs.lacinia :as l]
         '[com.walmartlabs.lacinia.schema :as l.schema])

(def schema
  (l.schema/compile
    ;; Wrap before compiling:
    (p/wrap-schema
      ;; Lacinia schema:
      '{:queries {:people {:type (list :Person)}}
        :objects {:Person {:fields {:name {:type String}
                                    :parents {:type (list :Person)}
                                    :children {:type (list :Person)}}}}}
      ;; Plusinia fetchers. Queries are defined using :Query type
      {:Query {:people (fn [_ nils]
                         (zipmap nils (repeat ["Alice" "Adam" "Beth" "Bob"])))}
       :Person {:name (fn [_ people]
                        (zipmap people people))
                :parents (fn [_ people]
                           (into {}
                                 (map (juxt identity
                                            {"Alice" []
                                             "Adam" []
                                             "Beth" ["Alice" "Adam"]
                                             "Bob" ["Alice" "Adam"]}))
                                 people))
                :children (fn [_ people]
                            (into {}
                                  (map (juxt identity
                                             {"Alice" ["Beth" "Bob"]
                                              "Adam" ["Beth" "Bob"]
                                              "Beth" []
                                              "Bob" []}))
                                  people))}})))
```

Now that we have a schema, we can query it as usual:

```clojure
(l/execute schema "
  {
    people {
      name
      children {
        name
      }
      parents {
        name
      }
    }
  }
  " {} {})
=> {:data {:people [{:name "Alice"
                     :children [{:name "Beth"} {:name "Bob"}]
                     :parents []}
                    {:name "Adam"
                     :children [{:name "Beth"} {:name "Bob"}]
                     :parents []}
                    {:name "Beth"
                     :children []
                     :parents [{:name "Alice"} {:name "Adam"}]}
                    {:name "Bob"
                     :children []
                     :parents [{:name "Alice"} {:name "Adam"}]}]}}
```
Fetchers for this query will be executed (sequentially by default) exactly 5 times:
```graphql
{ 
  people {         # batch 1: load a list of people
    name           # batch 2: for a set of people, fetch names  
    children {     # batch 3: for a set of people, fetch children
      name         # (batch 5, see below)
    }
    parents {      # batch 4: for a set of people, fetch parents
      name         # batch 5: for a set of all children and parents, fetch names
    }
  }
}
```

You are good to go! 

# Documentation

Plusinia uses query introspection capabilities of Lacinia to perform all fetching
and batching in the root query resolver. Then, it transforms the results in such a way
that resolvers of other fields are simple map lookups.

Even though Plusinia has terms like contexts and types, you shouldn't use any Lacinia code
in Plusinia fetchers: no `tag-with-type`, no `with-context`, no `resolve-promise`.

## Fetchers

Fetchers load requested data. Fetchers receive 2 arguments:
- batch key, that defaults to a merge of Plusinia context (`nil` by default) and field or 
  query args;
- a set of input values.

Any function is a valid fetcher, but you can also use `p/make-field-fetcher` and `p/make-query-fetcher`
to create fetcher maps that override how batch key is defined for the fetcher, and, in the case of
`make-query-fetcher`, also define what context keys from Lacinia context are transferred to Plusinia
context.

For example, if you want to forward a value to fetcher from code that calls `l/execute`, you can
do it like that:
```clojure
(def schema
  (l/compile
    (p/wrap-schema 
      ...
      {:Query {:people (p/make-query-fetcher (fn [{:keys [auth-key]} nils] 
                                               ...) 
                                             :context-keys #{:auth-key})}})))

(l/execute schema "..." {:auth-key ...auth-key-from-http-request})
```

By default, a fetcher should return a map from input value to result node (or a collection of 
result nodes), but depending on parallelization strategy it can return anything that will 
eventually resolve to the result map.

## Result nodes

Result node is a result value, that can optionally define GraphQL type and additional 
context for fetched children of the result value. Any object is a valid node without any 
explicit type or context.

You can use `p/make-node` to define type and/or additional context, e.g.:
```clojure
[(p/make-node created-event :type :EntityCreated :context {:tx 1})
 (p/make-node updated-event :type :EntityUpdated :context {:tx 2})]
```

## Query fetchers

Query resolvers in Lacinia are somewhat special because they receive synthetic 
`nil` as an input value. Plusinia fetchers are similarly special because they 
receive a `#{nil}` as input values and have to return `{nil ...result-node-or-nodes}`.

The boilerplate to execute query fetcher once and then return it for this set with `nil`
looks like that:
```clojure
(fn some-query-fetcher [ctx+args nils]
  (zipmap nils (repeat (execute-query! ctx+args))))
```

Plusinia could have used this boilerplate in its implementation to save you some typing, but
unfortunately it can't do that due to a pluggable parallelization - fetchers can return 
futures, or core.async chans, or anything else really! 

## Parallelization

When wrapping Lacinia schema with `p/wrap-schema`, you can provide a function that will
execute a collection of fetch functions to call. It should return a collection of result 
maps in the same order. Default implementation looks like that:
```clojure
(fn [fs]
  (mapv #(%) fs))
```
You can make your fetchers return futures, and then make your parallelization function
deref them, e.g.:
```clojure
(p/wrap-schema ...schema ...fetchers :execute-batches (fn [fs]
                                                        (mapv deref (mapv #(%) fs))))
```
Or you can make your fetchers return result maps and invoke them in a thread pool:
```clojure
(import '[java.util.concurrent Executors ExecutorService])

(def ^ExecutorService pool
  (Executors/newFixedThreadPool 32))

(p/wrap-schema ...schema ...fetchers :execute-batches #(mapv deref (.invokeAll pool %)))
```

## Batching

Plusinia batches calls to fetchers on:
- field fetcher function;
- batch key (defaults to a merge of context and args);
- nesting depth of the query.

1st arg to fetcher function is a batch key. By default, this is a merge of Plusinia
context (that defaults to `nil`) and field args. You can override the batch key on
per-field basis by providing a custom batch function, e.g.:
```clojure
{:Shape {:area (p/make-field-fetcher (fn [shape-type shapes]
                                       (case shape-type 
                                         ...))
                                     :batch-fn (fn [_ctx _args value]
                                                 (:type value)))}}
```

## Interfaces and unions

Plusinia supports interfaces and unions. It also allows specifying fetchers on the 
abstract type - both unions and interfaces. In this case, fetchers will be shared
between concrete types. Overriding is supported. Example:
```clojure

;; You'll probably need this in your project for simple "getter" fetchers :)
(defn- transform-value-fetcher [f]
  (fn [_ xs]
    (into {} (map (juxt identity f)) xs)))

(def schema
  (l.schema/compile
    (p/wrap-schema
      '{:queries {:changes {:type (list :Event)}}
        :interfaces {:Event {:fields {:id {:type String}}}}
        :objects {:EntityCreated {:implements [:Event]
                                  :fields {:id {:type String}
                                           :createdAt {:type String}}}
                  :EntityUpdated {:implements [:Event]
                                  :fields {:id {:type String}
                                           :updatedAt {:type String}}}}}
      {:Query {:changes (fn [_ nils]
                          ;; Queries of abstract types have to return explicitly typed nodes: 
                          (zipmap 
                            nils 
                            (repeat [(p/make-node {:id 1 :createdAt "2022-04-14"} :type :EntityCreated)
                                     (p/make-node {:id 1 :updatedAt "2022-04-15"} :type :EntityUpdated)
                                     (p/make-node {:id 2 :createdAt "2022-04-16"} :type :EntityCreated)])))}
       ;; Fetcher of abstract type:
       :Event {:id (transform-value-fetcher :id)}
       ;; Fetchers of concrete types:
       :EntityCreated {:createdAt (transform-value-fetcher :createdAt)}
       :EntityUpdated {:updatedAt (transform-value-fetcher :updatedAt)}})))

(l/execute schema "
  {
    changes {
      event: __typename
      id
      ... on EntityCreated {
        createdAt
      }
    }
  }
  " {} {})
=> {:data {:changes [{:event :EntityCreated
                      :id "1"
                      :createdAt "2022-04-14"}
                     {:event :EntityUpdated
                      :id "1"}
                     {:event :EntityCreated
                      :id "2"
                      :createdAt "2022-04-16"}]}}
```

# Closing note

That's all, folks!

If you are using this in production, please consider
[sponsoring my work](https://github.com/sponsors/vlaaad) on Plusinia.