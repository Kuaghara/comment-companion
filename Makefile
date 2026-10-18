.PHONY: bootstrap test build check sandbox clean

bootstrap: ## 下载离线 Kotlin/JUnit 工具链到 .toolchain/
	./scripts/bootstrap-toolchain.sh

test: ## 编译 main + test 并跑 JUnit
	./scripts/test.sh

build: ## 打包插件 zip 到 build/
	./scripts/build.sh

check: ## 检查 Kotlin 与 GoLand 平台 / Go PSI 的互操作
	./scripts/check-platform.sh

sandbox: ## 隔离沙盒启动 GoLand（DEBUG_PORT=5005 可开 JDWP）
	./scripts/dev-sandbox.sh

clean: ## 清理构建产物与沙盒
	rm -rf build .sandbox
