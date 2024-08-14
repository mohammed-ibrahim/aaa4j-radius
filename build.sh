

sendLocalHostRequest() {
	java -Drad.shared.secret=sharedsecret \
    -Drad.server.addr=127.0.0.1 \
    -Drad.server.port=7777 \
    -Drad.username=User1 \
    -Dlog4jRootPath=./logs/ -Dlog4j.configurationFile=./aaa4j-radius-server/log4j2.xml \
    -Drad.password=123124 \
    -Drad.use.pre.seeded.password=true \
    -Drad.nasIdentifier=SSID1 \
    -Drad.client.request.timeout.seconds=40 \
    -cp ./aaa4j-radius-client/target/aaa4j-radius-client-0.2.4-SNAPSHOT-jar-with-dependencies.jar org.aaa4j.radius.client.ClientRequestHelper
}

startServer() {
	java -Dlog4jRootPath=./logs/ \
	-Dlog4j.configurationFile=./aaa4j-radius-server/log4j2.xml \
	-Dradius.server.config.file=./serverConfig.properties \
	-cp ./aaa4j-radius-server/target/aaa4j-radius-server-0.2.4-SNAPSHOT-jar-with-dependencies.jar \
	org.aaa4j.radius.server.RadiusBootHelper
}


buildAndStartServer() {
	mvn clean -Dgpg.skip=true -DskipTests=true -Dcheckstyle.skip verify &&

	cp ./aaa4j-radius-server/target/aaa4j-radius-server-0.2.4-SNAPSHOT-jar-with-dependencies.jar ~/Desktop/CustomRadiusServer-$((RANDOM % 20000)).jar &&

	java -Dlog4jRootPath=./logs/ \
	-Dlog4j.configurationFile=./aaa4j-radius-server/log4j2.xml \
	-Dradius.server.config.file=./serverConfig.properties \
	-cp ./aaa4j-radius-server/target/aaa4j-radius-server-0.2.4-SNAPSHOT-jar-with-dependencies.jar \
	org.aaa4j.radius.server.RadiusBootHelper
}


case "${1-}" in
  0)
    buildAndStartServer
    ;;
  1)
    startServer
    ;;
  2)
    sendLocalHostRequest
    ;;

  *)
    echo $"0 Build and start server"
    echo $"1 Start server"
    echo $"2 Send localhost request"
    exit 1
    ;;
esac

