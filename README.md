# monday-example

## Getting started

### Prerequisites

* [Clojure](https://clojure.org/guides/getting_started)
* [mkcert](https://mkcert.dev/)

### Config

Copy `config.example.edn` to `config.edn` and configure your oauth credentials from your monday app

### Running everything

Make sure to first generate a cert with:

```shell script
make gen-cert
```

If this completes succesfully we can start `ngrok` and `shadow-cljs`,
run these two commands in two terminals:

Start `shadow-cljs`
```shell script
make watch-client
```

Start `ngrok`
```shell script
make tunnel
```

To start the application start a repl as follows, or use intellij
to start clojure (this is recommended):
```shell script
clj
```
