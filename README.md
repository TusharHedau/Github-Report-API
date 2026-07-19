# Github-Report-API
Spring Boot REST API that generates GitHub organization access reports using the GitHub REST API.
 🚀 GitHub Organization Access Report API

> **A scalable Spring Boot REST API that analyzes GitHub organizations and generates a user-centric repository access report.**

![Java](https://img.shields.io/badge/Java-21-orange?style=for-the-badge)
![Spring Boot](https://img.shields.io/badge/SpringBoot-3.3-green?style=for-the-badge)
![WebFlux](https://img.shields.io/badge/WebFlux-Reactive-blue?style=for-the-badge)
![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?style=for-the-badge)
![GitHub API](https://img.shields.io/badge/GitHub-REST_API-black?style=for-the-badge)

---
## 📖 Overview
Organizations often need to know **who has access to which repositories**. While GitHub exposes repository and collaborator APIs separately, there isn't a simple endpoint that provides an organization-wide access report.

This project bridges that gap by collecting repository and collaborator data from the GitHub REST API and transforming it into a clean, user-centric report.

Instead of answering:

> *"Who has access to this repository?"*

the API answers:

> *"Which repositories can this user access?"*

---

## ✨ Why This Project?

This project demonstrates production-oriented backend development practices:

- Clean layered architecture
- Reactive GitHub API integration using WebClient
- Caching to reduce repeated API calls
- Retry mechanism with exponential backoff
- Global exception handling
- Dockerized deployment
- OpenAPI documentation
- Unit testing

---

# 🏗 Solution Architecture

```
                 Client
                    │
                    ▼
          ReportController
                    │
                    ▼
            ReportService
                    │
                    ▼
            GithubService
                    │
                    ▼
             GithubClient
                    │
                    ▼
          GitHub REST API (v3)
```

---

# ⚙ Technology Stack

| Category | Technology |
|-----------|------------|
| Language | Java 21 |
| Framework | Spring Boot 3 |
| Reactive Client | Spring WebClient |
| Build Tool | Maven |
| API Documentation | Swagger / OpenAPI |
| Cache | Caffeine |
| Containerization | Docker |
| Testing | JUnit 5, Mockito |

---

# 📂 Project Structure

```
src
├── client
├── config
├── controller
├── dto
├── exception
├── model
├── service
├── util
└── resources
```

Each package has a single responsibility, making the project easier to maintain and extend.

---

# 🔐 Authentication

The application authenticates using a GitHub Personal Access Token.

Configure your token using an environment variable:

```properties
GITHUB_TOKEN=your_personal_access_token
```

Required scope:

```
read:org
```

---

# 🚀 Running the Application

Clone the repository

```bash
git clone https://github.com/TusharHedau/Github-Report-API.git
```

Navigate to the project

```bash
cd Github-Report-API
```

Build

```bash
mvn clean install
```

Run

```bash
mvn spring-boot:run
```

Application URL

```
http://localhost:8082
```

---

# 📡 REST Endpoint

```
GET /api/report/{organization}
```

Example

```
GET http://localhost:8080/api/report/tushar-test-org
```

---

# ✅ Sample Response

```json
{
  "organization": "tushar-test-org",
  "users": [
    {
      "username": "TusharHedau",
      "repositories": [
        "demo-repo"
      ]
    }
  ]
}
```

---

# 📸 Project Showcase

## 🖥 Application Running

> Add image here

```
screenshots/application-running.png
```

---

## 📘 Swagger UI

> Add image here

```
screenshots/swagger-ui.png
```

---

## 📬 API Response (Postman)

> Add image here

```
screenshots/postman-response.png
```

---

## 📁 Project Structure

> Add image here

```
screenshots/project-structure.png
```

---

# ⚡ Performance Considerations

Designed to efficiently support organizations with:

- 100+ repositories
- 1000+ collaborators

Key optimizations include:

- Reactive API calls using WebClient
- Configurable concurrency
- Caffeine caching
- Pagination support
- Retry with exponential backoff
- Rate-limit aware GitHub integration

---

# 🛡 Error Handling

The API returns structured responses for common scenarios:

| Status | Meaning |
|---------|----------|
| 400 | Invalid request |
| 401 | Invalid GitHub token |
| 404 | Organization not found |
| 429 | GitHub rate limit exceeded |
| 500 | Internal server error |

---

# 🧪 Testing

Run all tests

```bash
mvn test
```

The project includes unit tests for controllers, services, utility classes, caching, and exception handling.

---

# 🐳 Docker

Build

```bash
docker build -t github-report-api .
```

Run

```bash
docker-compose up
```

---

# 💡 Future Enhancements

- OAuth 2.0 Authentication
- Redis distributed caching
- Parallel report generation
- CSV / Excel export
- Repository permission levels
- Prometheus & Grafana monitoring

---

# 👨‍💻 Author

### **Tushar Hedau**

Backend Developer

GitHub: https://github.com/TusharHedau

LinkedIn: https://linkedin.com/in/tushar-hedau-606648291

---

## ⭐ Thank You

Thank you for reviewing this project.

Feedback and suggestions are always welcome.
