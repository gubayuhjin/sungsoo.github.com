---
layout: post
title: Apache Phoenix
date: 2015-05-10
categories: [computer science]
tags: [big data]

---

[![](http://sungsoo.github.com/images/scaling_data.png)](http://sungsoo.github.com/images/scaling_data.png)

---

# Apache Phoenix

Overview 
--------

Apache Phoenix is a relational database layer over HBase delivered as a
client-embedded JDBC driver targeting low latency queries over HBase
data. Apache Phoenix takes your SQL query, compiles it into a series of
HBase scans, and orchestrates the running of those scans to produce
regular JDBC result sets. The table metadata is stored in an HBase table
and versioned, such that snapshot queries over prior versions will
automatically use the correct schema. Direct use of the HBase API, along
with coprocessors and custom filters, results in
[performance](performance.html) on the order of milliseconds for small
queries, or seconds for tens of millions of rows.


Who is using Apache Phoenix? Read more [here...](who_is_using.html)
 
 ![](http://sungsoo.github.com/images/all.jpg)

Mission 
-------

Become the standard means of accessing HBase data through a
well-defined, industry standard API.

Quick Start 
-----------

Tired of reading already and just want to get started? Take a look at
our [FAQs](http://phoenix.apache.org/faq.html), listen to the Apache Phoenix talks from [Hadoop
Summit
2014](https://www.youtube.com/watch?v=f4Nmh5KM6gI&feature=youtu.be),
review the [overview
presentation](http://phoenix.apache.org/presentations/OC-HUG-2014-10-4x3.pdf),
and jump over to our quick start guide
[here](Phoenix-in-15-minutes-or-less.html).

SQL Support 
-----------

To see what’s supported, go to our [language
reference](http://phoenix.apache.org/language/index.html). It includes all typical SQL query
statement clauses, including `SELECT`, `FROM`, `WHERE`, `GROUP BY`,
`HAVING`, `ORDER BY`, etc. It also supports a full set of DML commands
as well as table creation and versioned incremental alterations through
our DDL commands. We try to follow the SQL standards wherever possible.

Use JDBC to get a connection to an HBase cluster like this:

```java
Connection conn = DriverManager.getConnection("jdbc:phoenix:server1,server2:3333");
```

where the connection string is composed of:

    jdbc:phoenix [ :<zookeeper quorum> [ :<port number> ] [ :<root node> ] ]

For any omitted part, the relevant property value,
hbase.zookeeper.quorum, hbase.zookeeper.property.clientPort, and
zookeeper.znode.parent will be used from hbase-site.xml configuration
file.

Here’s a list of what is currently **not** supported:

-   **Full Transaction Support**. Although we allow client-side batching
    and rollback as described [here](http://phoenix.apache.org/#transactions), we do not provide
    transaction semantics above and beyond what HBase gives you
    out-of-the-box.
-   **Relational operators**. Union, Intersect, Minus.
-   **Miscellaneous built-in functions**. These are easy to add - read
    this
    [blog](http://phoenix-hbase.blogspot.com/2013/04/how-to-add-your-own-built-in-function.html)
    for step by step instructions.

Schema
------

Apache Phoenix supports table creation and versioned incremental
alterations through DDL commands. The table metadata is stored in an
HBase table.

A Phoenix table is created through the [CREATE
TABLE](http://phoenix.apache.org/language/index.html#create) DDL command and can either be:

1.  **built from scratch**, in which case the HBase table and column
    families will be created automatically.
2.  **mapped to an existing HBase table**, by creating either a
    read-write TABLE or a read-only VIEW, with the caveat that the
    binary representation of the row key and key values must match that
    of the Phoenix data types (see [Data Types
    reference](http://phoenix.apache.org/language/datatypes.html) for the detail on the binary
    representation).
    -   For a read-write TABLE, column families will be created
        automatically if they don’t already exist. An empty key value
        will be added to the first column family of each existing row to
        minimize the size of the projection for queries.
    -   For a read-only VIEW, all column families must already exist.
        The only change made to the HBase table will be the addition of
        the Phoenix coprocessors used for query processing. The primary
        use case for a VIEW is to transfer existing data into a Phoenix
        table, since data modification are not allowed on a VIEW and
        query performance will likely be less than as with a TABLE.

All schema is versioned, and prior versions are stored forever. Thus,
snapshot queries over older data will pick up and use the correct schema
for each row.

### Salting 

A table could also be declared as salted to prevent HBase region hot
spotting. You just need to declare how many salt buckets your table has,
and Phoenix will transparently manage the salting for you. You’ll find
more detail on this feature [here](http://phoenix.apache.org/salted.html), along with a nice
comparison on write throughput between salted and unsalted tables
[here](http://phoenix.apache.org/performance.html#salting).

### Schema at Read-time 

Another schema-related feature allows columns to be defined dynamically
at query time. This is useful in situations where you don’t know in
advance all of the columns at create time. You’ll find more details on
this feature [here](dynamic_columns.html).

### Mapping to an Existing HBase Table 

Apache Phoenix supports mapping to an existing HBase table through the
[CREATE TABLE](http://phoenix.apache.org/language/index.html#create) and [CREATE
VIEW](http://phoenix.apache.org/language/index.html#create) DDL statements. In both cases, the
HBase metadata is left as-is, except for with CREATE TABLE the
[KEEP_DELETED_CELLS](http://hbase.apache.org/book/cf.keep.deleted.html)
option is enabled to allow for flashback queries to work correctly. For
CREATE TABLE, any HBase metadata (table, column families) that doesn’t
already exist will be created. Note that the table and column family
names are case sensitive, with Phoenix upper-casing all names. To make a
name case sensitive in the DDL statement, surround it with double quotes
as shown below:

```sql
CREATE VIEW “MyTable” (“a”.ID VARCHAR PRIMARY KEY)
```

For CREATE TABLE, an empty key value will also be added for each row so
that queries behave as expected (without requiring all columns to be
projected during scans). For CREATE VIEW, this will not be done, nor
will any HBase metadata be created. Instead the existing HBase metadata
must match the metadata specified in the DDL statement or a
`ERROR 505 (42000): Table is read only` will be thrown.

The other caveat is that the way the bytes were serialized in HBase must
match the way the bytes are expected to be serialized by Phoenix. For
VARCHAR,CHAR, and UNSIGNED_* types, Phoenix uses the HBase Bytes
utility methods to perform serialization. The CHAR type expects only
single-byte characters and the UNSIGNED types expect values greater than
or equal to zero.

Our composite row keys are formed by simply concatenating the values
together, with a zero byte character used as a separator after a
variable length type. For more information on our type system, see the
[Data Type](http://phoenix.apache.org/language/datatypes.html).

Transactions
------------

The DML commands of Apache Phoenix, [UPSERT
VALUES](http://phoenix.apache.org/language/index.html#upsert_values), [UPSERT
SELECT](http://phoenix.apache.org/language/index.html#upsert_select) and
[DELETE](http://phoenix.apache.org/language/index.html#delete), batch pending changes to HBase
tables on the client side. The changes are sent to the server when the
transaction is committed and discarded when the transaction is rolled
back. The only transaction isolation level we support is
TRANSACTION_READ_COMMITTED. This includes not being able to see your
own uncommitted data as well. Phoenix does not providing any additional
transactional semantics beyond what HBase supports when a batch of
mutations is submitted to the server. If auto commit is turned on for a
connection, then Phoenix will, whenever possible, execute the entire DML
command through a coprocessor on the server-side, so performance will
improve.

Most commonly, an application will let HBase manage timestamps. However,
under some circumstances, an application needs to control the timestamps
itself. In this case, a long-valued “CurrentSCN” property may be
specified at connection time to control timestamps for any DDL, DML, or
query. This capability may be used to run snapshot queries against prior
row values, since Phoenix uses the value of this connection property as
the max timestamp of scans.

Metadata 
--------

The catalog of tables, their columns, primary keys, and types may be
retrieved via the java.sql metadata interfaces: `DatabaseMetaData`,
`ParameterMetaData`, and `ResultSetMetaData`. For retrieving schemas,
tables, and columns through the DatabaseMetaData interface, the schema
pattern, table pattern, and column pattern are specified as in a LIKE
expression (i.e. % and _ are wildcards escaped through the character).
The table catalog argument to the metadata APIs deviates from a more
standard relational database model, and instead is used to specify a
column family name (in particular to see all columns in a given column
family).
