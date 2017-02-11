(ns puppetlabs.jdbc-util.core
  (:import [com.zaxxer.hikari HikariDataSource]
           [java.util.regex Pattern]
           [org.postgresql.util PGobject PSQLException]
           [org.postgresql.core Utils])
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :refer [trs trsn]]
            [puppetlabs.kitchensink.core :as ks]))

(defn escape-identifier
  [s]
  (.toString (org.postgresql.core.Utils/escapeIdentifier nil s)))

(defn escape-literal
  [s]
  (.toString (org.postgresql.core.Utils/escapeLiteral nil s true)))

(defn connection-pool
  "Given a DB spec map containing :subprotocol, :subname, :user, and :password
  keys, return a pooled DB spec map (one containing just the :datasource key
  with a pooled DataSource object as the value). The returned pooled DB spec
  can be passed directly as the first argument to clojure.java.jdbc's
  functions.

  Times out after 30 seconds and throws org.postgresql.util.PSQLException"
  [db-spec]
  (let [ds (doto (HikariDataSource.)
             (.setJdbcUrl (str "jdbc:"
                               (:subprotocol db-spec) ":"
                               (:subname db-spec)))
             (.setUsername (:user db-spec))
             (.setPassword (:password db-spec))
             (.setInitializationFailFast false))]
    {:datasource ds}))

(defmacro with-timeout [timeout-s default & body]
  `(let [f# (future (do ~@body))
         result# (deref f# (* 1000 ~timeout-s) ~default)]
     (future-cancel f#)
     result#))

(def db-status-timeout-secs 4)

(defn db-up?
  [db-spec]
  (let [result (with-timeout db-status-timeout-secs :timeout
                 (try (let [select-42 "SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"
                            [{:keys [answer]}] (jdbc/query db-spec [select-42])]
                        (= answer 42))
                      (catch Exception e
                        (log/warn e (trs "Status check of db failed with error:"))
                        false)))]
    (if (= :timeout result)
      (do (log/warn (trs "Database status check timed out after 4 seconds."))
          false)
      result)))

(defn db-exists?
  [admin-db-spec db-name]
  (-> (jdbc/query admin-db-spec ["SELECT 1 AS exists FROM pg_database WHERE datname = ?" db-name])
    first
    :exists
    (= 1)))

(defn create-db!
  "Given a DB spec that has a user with permission to create databases and that
  connects to a database that isn't `db-name`, the safe postgres identifier
  `db-name` (see the docstring of `safe-pg-identifier?` for the definition of
  'safe'), and the safe postgres identifier `db-owner`, create the
  database `db-name` owned by `db-owner`, with the DB's encoding set to UTF-8."
  [admin-db-spec db-name db-owner]
  (let [sql (format "CREATE DATABASE %s WITH OWNER %s ENCODING 'UTF8'" (escape-identifier db-name) (escape-identifier db-owner))]
    (jdbc/execute! admin-db-spec [sql] {:transaction? false})))

(defn drop-db!
  "Given a DB spec that has a user with permission to drop the database
  `db-name` and that connects to a database that isn't `db-name` and the safe
  postgres identifier `db-name` (see the docstring for `pg-identifier?` for the
  definition of 'safe'), drop the database named by `db-name`."
  [admin-db-spec db-name]
  (let [sql (format "DROP DATABASE IF EXISTS %s" (escape-identifier db-name))]
    (jdbc/execute! admin-db-spec [sql] {:transaction? false})
    nil))

(defn user-exists?
  [admin-db-spec user-name]
  (-> (jdbc/query admin-db-spec ["SELECT 1 AS present FROM pg_roles WHERE rolname = ?" user-name])
    first
    :present
    (= 1)))

(defn create-user!
  "Given a DB spec that has a user with permission to create users and that
  connects to a database that isn't `db-name`, the safe postgres identifier
  `db-name` (see the docstring of `safe-pg-identifier?` for the definition of
  'safe'), and the safe postgres identifier `db-owner`, create the
  database `db-name` owned by `db-owner`, with the DB's encoding set to UTF-8."
  [admin-db-spec username password]
  (let [sql (format "CREATE ROLE %s WITH LOGIN PASSWORD '%s'" (escape-identifier username) (escape-literal password))]
    (jdbc/execute! admin-db-spec [sql])
    nil))

(defn drop-user!
  [admin-db-spec username]
  (let [sql (format "DROP ROLE IF EXISTS %s" (escape-identifier username))]
    (jdbc/execute! admin-db-spec [sql])
    nil))

(defn public-tables
  "Get the names of all public tables in a database"
  [db-spec]
  (let [query "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"]
    (jdbc/query db-spec [query] {:row-fn :table_name})))

(defn drop-public-tables!
  "Drops all public tables in a database. Super dangerous."
  [db-spec]
  (when-let [tables (seq (public-tables db-spec))]
    (jdbc/db-do-commands db-spec (map #(format "DROP TABLE %s CASCADE" %) tables))))

(defn public-functions
  "Get the names of all public functions in a database"
  [db-spec]
  (let [query (str "SELECT ns.nspname || '.' || proname || '(' || oidvectortypes(proargtypes) || ')' AS function"
                   "  FROM pg_proc INNER JOIN pg_namespace ns ON (pg_proc.pronamespace = ns.oid)"
                   "  WHERE ns.nspname = 'public'")]
    (jdbc/query db-spec [query] {:row-fn :function})))

(defn drop-public-functions!
  "Drops all public functions in a database. Super dangerous."
  [db-spec]
  (when-let [functions (seq (public-functions db-spec))]
    (jdbc/db-do-commands db-spec (map #(format "DROP FUNCTION %s CASCADE" %) functions))))

(defn convert-result-arrays
  "Converts Java and JDBC arrays in a result set using the provided
  function (eg. vec, set). Values which aren't arrays are unchanged."
  ([result-set]
   (convert-result-arrays vec result-set))
  ([f result-set]
   (let [convert (fn [v] (cond
                           (ks/array? v) (f v)
                           (instance? java.sql.Array v) (f (.getArray v))
                           :else v))]
     (map #(ks/mapvals convert %) result-set))))

(defn convert-result-pgobjects
  "Converts PGObjects in a result set to be the value that they contain.
  Values which aren't arrays are unchanged."
  [result-set]
  (let [val-if-pgobj (fn [v] (if (instance? PGobject v)
                               (.getValue v)
                               v))]
    (map #(ks/mapvals val-if-pgobj %) result-set)))

(defn query
  "An implementation of query that returns a fully evaluated result (no
  lazy sequences, JDBCArray objects, or PGObjects)."
  [db sql-and-params]
  (let [convert (fn [rs]
                  (doall
                    (->> (jdbc/result-set-seq rs)
                      (convert-result-arrays vec)
                      (convert-result-pgobjects))))]
    (jdbc/db-query-with-resultset db sql-and-params convert)))

(defn ordered-group-by
  [f coll]
  (let [grouped-w-index (loop [i 0, groups (transient {}), coll (seq coll)]
                          (if-let [x (first coll)]
                            (let [k (f x)
                                  group (get groups k [i])
                                  groups' (assoc! groups k (conj group x))]
                              (recur (inc i) groups' (next coll)))
                            ;; else (nothing left in coll)
                            (persistent! groups)))]
    (->> (seq grouped-w-index)
      ; sort the groups by the index where the first member appeared
      (sort-by #(get-in % [1 0])))))

(defn aggregate-submap-by
  "Given a sequence of maps in results where each map contains agg-key
  and agg-val as keys, groups the maps that are identical except for the
  values in agg-key or agg-val. The values of agg-key and agg-val are
  turned into a map and stored in the resulting map under under-key."
  [agg-key agg-val under-key results]
  (for [[combined [_ & all]] (ordered-group-by #(dissoc % agg-key agg-val) results)]
    (assoc combined under-key (->> all
                                (map (juxt agg-key agg-val))
                                (remove (comp nil? first))
                                (into {})))))

(defn aggregate-column
  "Given a sequence of rows as maps, aggregate the values of `column`
  into a sequence under `under`, combining rows that are equal except
  for the value of `column`. Useful for consolidating the results of an
  outer join."
  [column under results]
  (for [[combined [_ & all]] (ordered-group-by #(dissoc % column) results)]
    (assoc combined under (map #(get % column) all))))

(defn- sequence-placeholder
  [xs]
  (str "("
       (->> (repeat (count xs) "?")
         (str/join ","))
       ")"))

(defn- replace-nth-?
  [^String s n replacement]
  (let [through-?-pattern (Pattern/compile (format "([^?]+?\\?){%d}" (inc n)))]
    (if-let [[match] (re-find through-?-pattern s)]
      (let [tail (.substring s (.length ^String match) (.length s))
            replaced (str/replace match #"(.*)\?$" (str "$1" replacement))]
        (str replaced tail))
      (throw (IllegalArgumentException. (trsn "There are no '?'s in the given string"
                                              "There are not {0} '?'s in the given string"
                                              n))))))

(defn expand-seq-params
  "A helper for prepared SQL statements with sequential parameters.
  Returns a new prepared statement with every `?` that corresponded to a
  sequential parameter expanded to a tuple literal of the appropriate
  length and flattened parameters."
  [[sql & parameters]]
  (let [seq-params-w-indices (->> (map vector parameters (range))
                               (filter (comp sequential? first)))
        [sql'] (reduce (fn [[sql shift] [param i]]
                         (let [shift' (+ shift (dec (count param)))
                               expansion (sequence-placeholder param)]
                           [(replace-nth-? sql (+ i shift) expansion) shift']))
                       [sql 0]
                       seq-params-w-indices)]
    (vec (conj (flatten parameters) sql'))))

(defn has-extension? [db extension]
  (-> (jdbc/query db ["select count(*) from pg_extension where extname = ?" extension])
      first
      :count
      pos?))

(defn get-sequence-name-for-column
  "Returns the name of the sequence associated with a column, or nil if there is
  no sequence."
  [db table column]
  (-> (jdbc/query db ["SELECT pg_get_serial_sequence(?, ?)" table column])
      first
      :pg_get_serial_sequence))

(defn quoted
  "Given a psql identifier like public.table-name or column-name, quotes it so
  that it is suitable to use in jdbc queries.
  E.g., public.table-name -> \"public\".\"table-name\"
        column-name -> \"column-name\""
  [id]
  (jdbc/as-sql-name (jdbc/quoted \") id))

(defn reconcile-sequence-for-column!
  "Finds the sequence associated with the given column and compares it to the
  max value in the column. If the sequence is lower, sets it equal to the max
  value.
  If the column has no associated sequence, throws an Exception."
  [db table column]
  (if-let [sequence-name (get-sequence-name-for-column db table column)]
    (let [select-last-value (str "(SELECT last_value FROM " (quoted sequence-name) ")")
          select-max-in-column (str "(SELECT MAX(" (quoted column) ") FROM " (quoted table) ")")]
      (jdbc/with-db-transaction [txn-db db]
        (jdbc/execute! txn-db [(format "LOCK TABLE \"%s\" IN EXCLUSIVE MODE" table)])
        (jdbc/query txn-db [(str "SELECT"
                                 "  CASE"
                                 "  WHEN " select-max-in-column " > " select-last-value
                                 "    THEN setval('" sequence-name "', " select-max-in-column ")"
                                 "  END")])))
    (throw (Exception. (format "No sequence found for column %s on table %s." table column)))))
