(defproject vmarcinko/teuta "0.1.0-SNAPSHOT"
  :description "Laughingly simple dependency injection container in Clojure"
  :url "https://github.com/vmarcinko/teuta"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :exclusions [log4j/log4j org.slf4j/slf4j-log4j12 org.slf4j/slf4j-jcl org.slf4j/slf4j-jdk14]
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]]
  :profiles {:1.5   {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :dev   {:dependencies [[midje "1.5.1"]]}
             :test  {:dependencies [[midje "1.5.1"]]}}
  :aliases {"test-all" ["with-profile" "test,1.5" "midje"]
            "codox" ["with-profile" "test" "doc"]}
  :plugins [[lein-ancient "0.4.4"]
            [lein-midje "3.0.0"]
            [codox "0.6.6"]]
  :min-lein-version "2.0.0")
