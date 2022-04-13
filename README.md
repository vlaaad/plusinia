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
execution times due to time windows where execution engine waits for batch to fill.

Plusinia provides an alternative solution for a use case where individual query execution 
time is more important than overall server performance.

# Installation

Use git dependency:

`io.github.vlaaad/plusinia {:git/tag "v1.18" :git/sha "5065817"}`

# Getting started

Plusinia works by wrapping Lacinia schema before it's compiled. It sets **all** query and 
object resolvers. It needs fetchers provided for every query and object field. The main
difference between Lacinia resolver and Plusinia fetcher:
- Lacinia resolver transforms input value to result;
- Plusinia fetcher transforms a set of input values to map from input value to result.

Let's start with creating a schema:
```clojure
(require '[io.github.vlaaad.plusinia :as p]
         '[com.walmartlabs.lacinia :as l]
         '[com.walmartlabs.lacinia.schema :as l.schema])

(def schema
  (l.schema/compile
    (p/wrap-schema
      '{:queries {:people {:type (list :Person)}}
        :objects {:Person {:fields {:name {:type String}
                                    :parents {:type (list :Person)}
                                    :children {:type (list :Person)}}}}}
      ;; queries are defined using :Query type
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

Now that we have a schema, we can query it:

```clojure
(l/execute 
  schema 
  "
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
  " 
  {}
  {})
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
Fetchers for this query will be executed (sequentially) exactly 5 times:
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

# How it works

Plusinia uses query introspection capabilities of Lacinia to perform all fetching 
and batching in the root query resolver. Then, it transforms the results so resolvers of 
other fields are simple map lookups. 




# Tips and tricks

TODO: 
- query resolvers (nils...)
- batches: context keys, contexts, batch-fn.
  Plusinia batches resolvers by a permutation of:
  - field fetcher fn;
  - batch key (defaults to a merge of context and args, can be overridden);
  - nesting depth of the query.
- interfaces and unions
- simple value transformers
- parallelization (futures, thread pool)