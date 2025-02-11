(ns convex-web.account
  (:require [datalevin.core :as d]
            [convex-web.convex :as convex]))

(defn find-all [db]
  (d/q '[:find [(pull ?e [* {:convex-web.account/faucets [*]}]) ...]
         :in $
         :where [?e :convex-web.account/address]]
       db))

(defn find-by-address [db addressable]
  (d/q '[:find (pull ?e [* {:convex-web.account/faucets [*]}]) .
         :in $ ?address
         :where [?e :convex-web.account/address ?address]]
       db (.longValue (convex/address addressable))))
