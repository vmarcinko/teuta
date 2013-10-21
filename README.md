```clojure
[vmarcinko/teuta "0.1.0"]
```

# Teuta, a laughingly simple dependency injection container in Clojure

If you like to give more structure to your Clojure applications, and you feel some component container utilizing dependency injection a-la [Spring](http://projects.spring.io/spring-framework/) would help you with that, and all you want is something *simple that just works*, then Teuta may be just a library for you.

## The features
 * Ultra-lightweight **all-Clojure** library
 * Simple map-based declarative way to specify components and their dependecies; no arcane XML!
 * Simple map-based parametrization of your system configuration
 * Starting and stopping of lifecycle-aware components
 * Plays nicely wth Stuart Sierra's ["reloaded" workflow](http://thinkrelevance.com/blog/2013/06/04/clojure-workflow-reloaded)

## User Guide

### Introduction

Teuta is *component* container, so one can wonder what a *component* means? It is certainly a vague term, but let's imagine it's something that:

 * groups related functions (similar to namespace)
 * can have a lifecycle
 * can be configured externally
 * can be wired externally to other components

The purpose of componentization is to give some structure to our applications, so one can reason about the system more easily. Clojure language construct that is most suitable for defining a component is probably a record, 
although some other constructs can be also used to make a component, so Teuta doesn't impose any hard constraints about that as you will soon see.

### Library Dependencies

Add the necessary dependency to your [Leiningen](http://leiningen.org/) `project.clj` and `require` the library in your ns:

```clojure
[vmarcinko/teuta "0.1.0"] ; project.clj

(ns my-app (:require [vmarcinko.teuta :as teuta])) ; ns
```
### Container Specification

Anyway, to create a component container, we have to start by defining a specification, and it is simply a map of entries - [component-id component-specification].

Component ID is usually a keyword, though String or some other value can be used. Component specification is vector of [component-factory-fn & args], so a component can be constructed later, during container construction time, 
by evaluating factory function with given arguments. So you see, this is just an ordinary function, and a component can be constructed in any arbitrary way, though maybe most usual way 
would be to use records and their map factory functions which are very descriptive.

If a component depends upon some other component, then it should be configured to use it. Refering to other components is done via
```clojure
(teuta/comp-ref some-comp-id)
```
If components form circular dependencies, exception will be reported during container construction time.

Similarly, if we want to parametrize some piece of component configuration, then we simply do that via:
```clojure
(teuta/param-ref some-param-id-path)
```

So, specification would look something like:
```clojure
(def my-specification {:my-comp-1 [mycompany.myapp/map->MyComp1Record {
									:my-param-1 "Some string"
									:my-param-2 334
									:my-param-3 (teuta/param-ref :comp-1-settings :some-remote-URL)
									:comp2-param (teuta/comp-ref :my-comp-2)}]
					   :my-comp-2 [mycompany.myapp/map->MyComp1Record {
									:my-param-1 6161
									:my-param-2 (atom nil)
									:my-param-3 (teuta/param-ref :comp-2-settings :admin-email)}]})
```

Since whole specification is simply a regular map, it is useful to have some common map containing always present components, and have separate profile-specific maps with components for production, test, development...
That way you simply merge those maps together to construct desired final specification.

### Component Lifecycle

If a component's functions depend upon some side-effecting logic being executed prior to using them, then a component can implement 
*vmarcinko.teuta/Lifecycle* protocol. The protocol combines *start* and *stop* functions which will get called during starting and stopping of a container.

Container is started by:
```clojure
(teuta/start-container my-container)
```
Components are started in dependency order. If any component raises exception during startup, the container will automatically perform stopping of all already started components, and rethrow the exception afterwards.

Likewise, stopping of container is done via:
```clojure
(teuta/stop-container my-container)
```
If any component raises exception during this process, the exception will be logged and the process will continue with other components.

### Container Construction

Once we have our specification, we can simply create a container by calling

```clojure
(def my-container (teuta/create-container my-specification my-parameters))
```
The container is just a sorted map of [component-id component] entries.
When the container map is printed, in order to make it a bit more clear, referred components will be printed as << component *some-comp-id* >>.

### Logging

Teuta uses [clojure.tools.logging](https://github.com/clojure/tools.logging), thus one can pick desired logging library.

## Example

Here we define 2 components - **divider** and **alarmer**. 

**Divider** takes 2 numbers and returns result of their division. Let's define working interface of the component as protocol, so we can allow many implementations.
```clojure
(ns vmarcinko.teutaexample.divider)

(defprotocol Divider
  (divide [this n1 n2] "Divides 2 numbers and returns vector [:ok result]. In case of error, [:error "Some error description"] will be returned"))
```
Unlike here, component interfaces will mostly contain multiple related functions. 

Request-handler components, such as web handlers, usually don't have a working interface since we don't "pull" them for some functionality, 
they just need to be started and stopped by container, thus they just implement Lifecycle protocol.

Default implementation of our divider component will naturally return the result of dividing the numbers, but in case of division by zero, it will also send notification about the thing to alarmer component. 
Placing component implementation in separate namespace is just a nice way of separating component interface and implementation.
```clojure
(ns vmarcinko.teutaexample.divider-impl
  (:require [vmarcinko.teutaexample.alarmer :as alarmer]
            [vmarcinko.teutaexample.divider :as divider]
            [vmarcinko.teuta :as teuta]))

(defrecord DefaultDividerImpl [alarmer division-by-zero-alarm-text]

  divider/Divider
  (divide [_ n1 n2]
    (if (= n2 0)
      (do
        (alarmer/raise-alarm alarmer division-by-zero-alarm-text)
        [:error "Division by zero error"])
      [:ok (/ n1 n2)])))
```

**Alarmer** interface is defined as follows:
```clojure
(ns vmarcinko.teutaexample.alarmer)

(defprotocol Alarmer
  (raise-alarm [this description] "Raise alarm about some issue. Returns nil."))
```
Default implementation of alarmer "sends" alarm notifications to preconfigured email addresses. For this example, sending an email is just printing the message to stdout. 
It also prints alarm count, which is mutable state of this component, and is held in an atom passed to it during construction. Atom state is initialized and cleaned up during lifecycle phases - start and stop.

```clojure
(ns vmarcinko.teutaexample.alarmer-impl
  (:require [vmarcinko.teutaexample.alarmer :as alarmer]
            [vmarcinko.teuta :as teuta]))

(defrecord DefaultAlarmerImpl [notification-emails alarm-count]

  alarmer/Alarmer
  (raise-alarm [_ description]
    (let [new-alarm-count (swap! alarm-count inc)]
      (println (str "Alarm Nr." new-alarm-count " raised: '" description "'; notifying emails: " notification-emails))))

  teuta/Lifecycle
  (start [_]
    (reset! alarm-count 0))
  (stop [_]
    (reset! alarm-count nil)))
```


So let's finally create container specification and wire these 2 components. We will also extract alarmer email addresses as application parameters.

```clojure
(def my-parameters {:alarmer-settings {:emails ["admin1@mycompany.com" "admin2@mycompany.com"]}})

(def my-specification
  {:my-divider [vmarcinko.teutaexample.divider-impl/map->DefaultDividerImpl
                {:alarmer 						(teuta/comp-ref :my-alarmer)
                 :division-by-zero-alarm-text 	"Arghhh, somebody tried to divide with zero!"}]

   :my-alarmer [vmarcinko.teutaexample.alarmer-impl/map->DefaultAlarmerImpl
                {:notification-emails 	(teuta/param-ref :alarmer-settings :emails)
                 :alarm-count 			(atom nil)}]})
```

Now we can constract the container, start it and try out dividing 2 numbers via divider component.
```clojure
(def my-container (teuta/create-container my-specification))

(teuta/start-container my-container)

(vmarcinko.teutaexample.divider/divide (:my-divider my-container) 3 44)
=> [:ok 3/44]
```

In order to call divide function of Divider protocol "from outside", we needed to pick divider component from the container first.
But if request-handling piece of application is also a component in container, as could be the case with some web handler serving HTTP requests to our Divider/divide function,
then container specification will handle wiring specified divider component. Let's create such a **web handler** component using popular Jetty web server:

```clojure
(ns vmarcinko.teutaexample.web-handler
  (:require [ring.adapter.jetty :as jetty]
            [vmarcinko.teuta :as teuta]
            [ring.middleware.params :as ring-params]
            [vmarcinko.teutaexample.divider :as divider]))

(defn- create-handler [divider]
  (fn [request]
    (let [num1 (Integer/parseInt ((:params request) "arg1"))
          num2 (Integer/parseInt ((:params request) "arg2"))
          result (nth (divider/divide divider num1 num2) 1)]
      {:status 200
       :headers {"Content-Type" "text/html"}
       :body (str "<h1>Result of dividing " num1 " with " num2 " is: " result " </h1>")})))

(defn- ignore-favicon [handler]
  (fn [request]
    (when-not (= (:uri request) "/favicon.ico")
      (handler request))))

(defrecord DefaultWebHandler [port divider server]
  teuta/Lifecycle
  
  (start [this]
    (reset! server
      (let [handler (->> (create-handler divider)
                         ring-params/wrap-params
                         ignore-favicon)]
        (jetty/run-jetty handler {:port port :join? false}))))

  (stop [this]
    (.stop @server)
    (reset! server nil)))
```
Jetty server is held in an atom, and is started on configured port during lifecycle start phase. As can be seen, divider component is the only dependency of this component, 
and request URL parameters "arg1" and "arg2" are passed as arguments to Divider/divide function. We added also favicon request ignoring handler to simplify testing it via browser.
This component requires popular [Ring](https://github.com/ring-clojure/ring) library, so one needs to add that to project.clj as:
```clojure
:dependencies [[ring/ring-core "1.2.0"]
			   [ring/ring-jetty-adapter "1.2.0"]
			   ...
```
			 
Let's expand our specification to wire this new component.
```clojure
(def my-parameters { ...previous parameters ...
					:web-handler-settings {:port 3500}})

(def my-specification
  { ....previous components ....
   :my-web-handler [vmarcinko.teutaexample.web-handler/map->DefaultWebHandler
                    {:port (teuta/param-ref :web-handler-settings :port)
                     :divider (teuta/comp-ref :my-divider)
                     :server (atom nil)}]})
```
Now, after tha container has been started, we can try out HTTP request:

[http://localhost:3500?arg1=3&arg2=44](http://localhost:3500?arg1=3&arg2=44)

Division result should be returned as HTML response. Division with zero should print tha alarming message to REPL output.


## Contact & contribution

Please use the [project's GitHub issues page](https://github.com/vmarcinko/teuta/issues) for project questions/comments/suggestions/whatever. Am very open to ideas if you have any!

Otherwise reach me at GMail where my account is same as this one at GitHub. Cheers!

## License

Copyright &copy; 2013 Vjeran Marcinko. Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
