pipeline {
    agent any
    parameters {
        choice(name: 'DEPLOY_ENV', choices: ['dev', 'prod'], description: '배포 환경')
        gitParameter(
            name: 'GIT_REF',
            type: 'PT_BRANCH_TAG',
            branchFilter: 'origin/(.*)',
            tagFilter: '*',
            defaultValue: 'main',
            sortMode: 'DESCENDING_SMART',
            selectedValue: 'DEFAULT',
            quickFilterEnabled: true,
            description: '배포할 브랜치 또는 태그'
        )
    }
    environment {
        REPO_URL = "https://github.com/lshdainty/groo-damda-back.git"
        IMAGE_NAME = "groo-damda-back"
        SRC_DIR = "${env.POREST_BASE_DIR}/src/desk-back"
        ENV_FILE_DEV = "${env.POREST_BASE_DIR}/backend/dev/desk/dev.env"
        ENV_FILE_PROD = "${env.POREST_BASE_DIR}/backend/prod/desk/prod.env"
        // 업로드 파일 영속 경로(호스트). 컨테이너는 쓰기 계층이 재배포마다 날아가므로
        // 반드시 호스트 디렉터리를 마운트한다. dev/prod 는 저장소를 분리한다.
        FILE_DIR_DEV = "${env.POREST_BASE_DIR}/backend/dev/desk/files"
        FILE_DIR_PROD = "${env.POREST_BASE_DIR}/backend/prod/desk/files"
        // 애플리케이션 로그(logback RollingFileAppender) 영속 경로.
        // 컨테이너 내부에 쌓으면 재배포마다 사라지고, 비-root 유저는 /home 에 만들 수도 없다.
        LOG_DIR_DEV = "${env.POREST_BASE_DIR}/backend/dev/desk/logs"
        LOG_DIR_PROD = "${env.POREST_BASE_DIR}/backend/prod/desk/logs"
        // 컨테이너 실행 유저(porest). 마운트 디렉터리 소유권과 이미지 내 UID 가 같아야
        // 비-root 프로세스가 볼륨에 쓸 수 있다 — Dockerfile build-arg 와 chown 에 같은 값을 쓴다.
        APP_UID = "1000"
        APP_GID = "1000"
        CONTAINER_NAME = "desk-backend"
    }
    stages {
        stage('Validate') {
            steps {
                script {
                    // 운영은 릴리즈 태그(vX.Y.Z)만 배포할 수 있다. main 등 브랜치는 거부.
                    if (params.DEPLOY_ENV == 'prod' && !(params.GIT_REF ==~ /v\d+\.\d+\.\d+/)) {
                        error "운영 배포는 릴리즈 태그(vX.Y.Z)만 허용됩니다. 선택된 값: ${params.GIT_REF}"
                    }
                }
            }
        }
        stage('Checkout') {
            steps {
                dir("${SRC_DIR}") {
                    checkout([$class: 'GitSCM',
                        branches: [[name: params.GIT_REF.startsWith('v') ? "refs/tags/${params.GIT_REF}" : "*/${params.GIT_REF}"]],
                        userRemoteConfigs: [[url: "${REPO_URL}", credentialsId: 'github-credentials']]
                    ])
                }
            }
        }
        stage('Resolve Version') {
            steps {
                dir("${SRC_DIR}") {
                    script {
                        if (params.DEPLOY_ENV == 'prod') {
                            // Validate 에서 vX.Y.Z 임이 보장됨 — 선택한 태그가 곧 버전
                            env.APP_VERSION = params.GIT_REF
                        } else {
                            // dev 는 태그 위여도 --long 으로 -0-g<hash> 를 붙여
                            // 운영 이미지(vX.Y.Z)와 이름이 절대 겹치지 않게 한다
                            env.APP_VERSION = sh(
                                script: 'git describe --tags --match \'v*\' --always --long 2>/dev/null || echo unknown',
                                returnStdout: true
                            ).trim()
                        }
                        echo "APP_VERSION = ${env.APP_VERSION}"
                        // 버전은 아래 셸 명령(docker build · docker run)에 들어간다. dev 는 git describe 결과라
                        // 태그 이름이 그대로 섞인다 — 태그를 올릴 수 있는 사람이 이름에 셸 문법을 심으면 Jenkins 에서
                        // 실행된다. 운영 Validate 의 형식 검사처럼 허용 문자만 통과시키고, 셸에는 문자열을 이어 붙이지
                        // 않고 환경변수(env.APP_VERSION → "$APP_VERSION")로만 넘긴다(보안 점검 SUP-2).
                        if (!(env.APP_VERSION ==~ /[A-Za-z0-9._-]+/)) {
                            error "버전 문자열에 허용되지 않는 문자가 있습니다(영문·숫자·. _ - 만 허용): '${env.APP_VERSION}'"
                        }
                    }
                }
            }
        }
        stage('Docker Build') {
            steps {
                dir("${SRC_DIR}") {
                    withCredentials([usernamePassword(credentialsId: 'github-credentials', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                        // 토큰은 --secret 으로 넘긴다 — BuildKit 이 RUN 동안에만 tmpfs 로
                        // 붙여 주고 레이어·이미지 설정에 남기지 않는다(Dockerfile 주석 참고).
                        //
                        // --build-arg GITHUB_TOKEN 은 일부러 남긴다. Jenkins 는 이 Jenkinsfile 을
                        // 항상 main 에서 읽지만(job 설정이 CpsScmFlowDefinition · */main ·
                        // lightweight 이다) Dockerfile 은 선택한 GIT_REF 에서 온다. 이 변경
                        // 이전에 딴 릴리스 태그의 Dockerfile 은 아직 ARG GITHUB_TOKEN 을 쓰므로,
                        // 여기서 빼면 그 태그를 다시 배포(롤백)할 때 GitHub Packages 인증이
                        // 깨진다. 새 Dockerfile 은 이 build-arg 를 안 쓰고 경고만 낸다.
                        // 옛 태그를 다시 배포할 일이 없어지면 이 --build-arg 를 지운다.
                        sh 'docker build --secret id=github_token,env=GH_TOKEN --build-arg GITHUB_ACTOR=$GH_USER --build-arg GITHUB_TOKEN=$GH_TOKEN --build-arg APP_UID=' + APP_UID + ' --build-arg APP_GID=' + APP_GID + ' -t ' + IMAGE_NAME + ':latest -t ' + IMAGE_NAME + ':"$APP_VERSION" .'
                    }
                }
            }
        }
        stage('Deploy to Dev') {
            when { expression { params.DEPLOY_ENV == 'dev' } }
            steps {
                echo "Deploying Desk Backend to Development..."
                sh """
                    mkdir -p ${FILE_DIR_DEV} ${LOG_DIR_DEV}
                    chown -R ${APP_UID}:${APP_GID} ${FILE_DIR_DEV} ${LOG_DIR_DEV}
                    docker stop ${CONTAINER_NAME}-dev || true
                    docker rm ${CONTAINER_NAME}-dev || true
                    docker run -d --name ${CONTAINER_NAME}-dev \
                        --hostname ${CONTAINER_NAME}-dev \
                        --restart unless-stopped \
                        --network ${env.DEV_NETWORK} \
                        --env-file ${ENV_FILE_DEV} \
                        -v ${FILE_DIR_DEV}:/app/files \
                        -v ${LOG_DIR_DEV}:/app/logs \
                        -e FILE_ROOT_PATH=/app/files \
                        -e LOG_PATH=/app/logs \
                        --log-opt max-size=10m --log-opt max-file=3 \
                        -e SPRING_PROFILES_ACTIVE=dev \
                        -e LOKI_URL=${env.LOKI_URL} \
                        -e APP_VERSION="\$APP_VERSION" \
                        ${IMAGE_NAME}:"\$APP_VERSION"

                    # 모니터링 망 추가 연결 — prometheus 스크레이프·loki 로그 (DB 망과 분리)
                    docker network connect monitoring-network ${CONTAINER_NAME}-dev || true

                    # 헬스 게이트 — HEALTHCHECK 가 healthy 가 될 때까지 대기, 못 뜨면 배포 실패
                    st=starting
                    for i in \$(seq 1 60); do
                        st=\$(docker inspect -f '{{.State.Health.Status}}' ${CONTAINER_NAME}-dev 2>/dev/null || echo none)
                        [ "\$st" = healthy ] && break
                        [ "\$st" = unhealthy ] && break
                        sleep 3
                    done
                    if [ "\$st" != healthy ]; then
                        echo "헬스 게이트 실패: 상태=\$st"
                        docker logs --tail 80 ${CONTAINER_NAME}-dev || true
                        exit 1
                    fi
                """
            }
        }
        stage('Approval for Prod') {
            when { expression { params.DEPLOY_ENV == 'prod' } }
            steps {
                script {
                    input(
                        id: 'DeployToProd',
                        message: "운영 서버에 배포하시겠습니까?",
                        ok: '배포'
                    )
                }
            }
        }
        stage('Deploy to Prod') {
            when { expression { params.DEPLOY_ENV == 'prod' } }
            steps {
                echo "Deploying Desk Backend to Production..."
                sh """
                    mkdir -p ${FILE_DIR_PROD} ${LOG_DIR_PROD}
                    chown -R ${APP_UID}:${APP_GID} ${FILE_DIR_PROD} ${LOG_DIR_PROD}
                    docker stop ${CONTAINER_NAME}-prod || true
                    docker rm ${CONTAINER_NAME}-prod || true
                    docker run -d --name ${CONTAINER_NAME}-prod \
                        --hostname ${CONTAINER_NAME}-prod \
                        --restart unless-stopped \
                        --network ${env.PROD_NETWORK} \
                        --env-file ${ENV_FILE_PROD} \
                        -v ${FILE_DIR_PROD}:/app/files \
                        -v ${LOG_DIR_PROD}:/app/logs \
                        -e FILE_ROOT_PATH=/app/files \
                        -e LOG_PATH=/app/logs \
                        --log-opt max-size=10m --log-opt max-file=3 \
                        -e SPRING_PROFILES_ACTIVE=prod \
                        -e LOKI_URL=${env.LOKI_URL} \
                        -e APP_VERSION="\$APP_VERSION" \
                        ${IMAGE_NAME}:"\$APP_VERSION"

                    # 모니터링 망 추가 연결 — prometheus 스크레이프·loki 로그 (DB 망과 분리)
                    docker network connect monitoring-network ${CONTAINER_NAME}-prod || true

                    # 헬스 게이트 — HEALTHCHECK 가 healthy 가 될 때까지 대기, 못 뜨면 배포 실패
                    st=starting
                    for i in \$(seq 1 60); do
                        st=\$(docker inspect -f '{{.State.Health.Status}}' ${CONTAINER_NAME}-prod 2>/dev/null || echo none)
                        [ "\$st" = healthy ] && break
                        [ "\$st" = unhealthy ] && break
                        sleep 3
                    done
                    if [ "\$st" != healthy ]; then
                        echo "헬스 게이트 실패: 상태=\$st"
                        docker logs --tail 80 ${CONTAINER_NAME}-prod || true
                        exit 1
                    fi
                """
            }
        }
    }
}
