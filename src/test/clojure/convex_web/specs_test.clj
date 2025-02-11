(ns convex-web.specs-test
  (:require 
   [convex-web.specs]
   [convex-web.convex :as convex]
   [convex-web.config :as config]
   
   [clojure.test :refer :all]
   [clojure.spec.alpha :as s]
   
   [expound.alpha :as expound])
  
  (:import (convex.core.init Init)))

(s/check-asserts true)

(def TEST_ADDRESS 1)

(deftest command-specs
  (testing "Incoming Query"
    (let [q #:convex-web.query {:source "1"
                                :language :convex-lisp}

          c #:convex-web.command {:mode :convex-web.command.mode/query
                                  :query q}]
      (is (s/assert :convex-web/command c)))

    (let [q #:convex-web.query {:source "1"
                                :language :convex-lisp
                                :address TEST_ADDRESS}

          c #:convex-web.command {:mode :convex-web.command.mode/query
                                  :query q}]
      (is (s/assert :convex-web/command c))))

  (testing "Incoming Transaction"
    (let [t #:convex-web.transaction{:type :convex-web.transaction.type/invoke
                                     :source "1"
                                     :language :convex-lisp
                                     :target 1}

          c #:convex-web.command {:address TEST_ADDRESS
                                  :mode :convex-web.command.mode/transaction
                                  :transaction t}]
      (is (s/assert :convex-web/command c)))

    (let [t #:convex-web.transaction{:type :convex-web.transaction.type/transfer
                                     :amount 1
                                     :target 1}

          c #:convex-web.command {:address TEST_ADDRESS
                                  :mode :convex-web.command.mode/transaction
                                  :transaction t}]
      (is (s/assert :convex-web/command c))))

  (testing "Running Transaction"
    (let [t #:convex-web.transaction {:type :convex-web.transaction.type/invoke
                                      :source "1"
                                      :language :convex-lisp
                                      :target 1}

          c #:convex-web.command {:id 1
                                  :address TEST_ADDRESS
                                  :status :convex-web.command.status/running
                                  :mode :convex-web.command.mode/transaction
                                  :transaction t}]
      (is (s/assert :convex-web/command c))))

  (testing "Running Query"
    (let [q #:convex-web.query {:source "1"
                                :language :convex-lisp
                                :address TEST_ADDRESS}

          c #:convex-web.command {:id 1
                                  :status :convex-web.command.status/running
                                  :mode :convex-web.command.mode/query
                                  :query q}]
      (is (s/assert :convex-web/command c))))

  (testing "Successful Query"
    (let [q #:convex-web.query {:source "1"
                                :language :convex-lisp
                                :address TEST_ADDRESS}

          c #:convex-web.command {:id 1
                                  :status :convex-web.command.status/success
                                  :mode :convex-web.command.mode/query
                                  :query q
                                  :object 1}]
      (is (s/assert :convex-web/command c))))

  (testing "Error Query"
    (let [q #:convex-web.query {:source "1"
                                :language :convex-lisp
                                :address TEST_ADDRESS}

          c #:convex-web.command {:id 1
                                  :status :convex-web.command.status/error
                                  :mode :convex-web.command.mode/query
                                  :query q
                                  :error {:message "Error"}}]
      (is (s/assert :convex-web/command c)))))

(deftest config-test
  (testing "Test configuration"
    (is (s/valid? :convex-web/config (config/read-config :test)))))