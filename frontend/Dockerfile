FROM node:20-alpine

WORKDIR /app

# Cache npm install — only reruns when package files change
COPY package*.json ./
RUN npm install

COPY . .

EXPOSE 5173

CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
