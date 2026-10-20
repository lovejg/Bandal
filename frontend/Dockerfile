# 화면 이미지(bandal-web). Bandal의 입구다. 화면 파일을 내주고 /api는 backend로 넘긴다.
# 빌드 결과(HTML, JS)는 CPU와 상관없는 파일이라, 백엔드처럼 1단계는 만드는 컴퓨터의 CPU로 돌린다

# 1단계: vite로 화면 파일을 만든다
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /app
# 의존성 목록만 먼저 복사한다. 소스만 바뀌면 npm ci 층을 다시 쓴다
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# 2단계: Nginx에 화면 파일과 설정만 담는다
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
