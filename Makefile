.PHONY: demo test
demo:
	./gradlew -q run --args=demo
test:
	./gradlew test
