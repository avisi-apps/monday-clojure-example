gen-cert:
	(mkdir ssl \
	&& cd ssl \
	&& mkcert -install -pkcs12 localhost \
	&& keytool -importkeystore -destkeystore keystore.jks -srcstoretype PKCS12 -srckeystore localhost.p12 --srcstorepass changeit -deststorepass changeit)

watch-client:
	yarn install
	clj -A:shadow-cljs watch app

tunnel: yarn.lock
	yarn ngrok http 3000 --bind-tls true --region eu

yarn.lock: node_modules package.json
	yarn install
	touch yarn.lock

node_modules:
	mkdir -p $@

lint:
	clj -A:lint:fix

clean:
	rm -rf node_modules
	rm -rf target
