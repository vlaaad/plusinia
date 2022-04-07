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

# How it works

Plusinia uses query introspection capabilities of Lacinia to perform all fetching 
and batching in the root query resolver. Then, it transforms the results so resolvers of 
other fields are simple map lookups. 

The main difference between Lacinia resolver and Plusinia fetcher is:
- Lacinia resolver is given a value and returns a resolved result;
- Plusinia fetcher is given a set of values and returns a map from value to result.

Also, Lacinia query resolvers receive synthetic `nil` as input value, while Plusinia query
fetchers don't receive a useless input value at all.

Plusinia batches resolvers by field + context + args + nesting depth of the query.

Here is an illustrative example:
```graphql
{ 
  people {         # query 1: load a list of people  
    children {     # query 2: for a set of people, load children
      name         # query 4: see below
    }
    parents {      # query 3: for a set of people, load parents
      name         # query 4: for a set of all children and parents, load names
    }
  }
}
```

# Using the library

TODO...