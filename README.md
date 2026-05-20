# MyStore - eCommerce Platform

MyStore is a full-stack, microservices-based eCommerce application built with Spring Boot and React. The platform is designed for scalability and robustness, separating core domains into independent microservices.

## Architecture

The backend consists of the following Spring Boot microservices:

- **Auth Service**: Handles user registration, authentication, and JWT token generation.
- **Product Service**: Manages the product catalog, including product images, details, and inventory.
- **Order Service**: Manages customer orders and checkout processes.
- **Gateway Service**: Acts as the API Gateway (Spring Cloud Gateway) routing requests to respective microservices and handling circuit breaking (Resilience4j).
- **Discovery Service**: Eureka server for service registry and discovery.

## Tech Stack

- **Backend**: Java, Spring Boot, Spring Cloud, Spring Security, Hibernate/JPA, Eureka, Gateway, Feign Clients.
- **Frontend**: React.js, Vite.
- **Database**: MySQL.
- **Payments**: Razorpay Integration.
- **Email**: SMTP Mail support.

## Getting Started

### Prerequisites

- Java 17+
- Node.js 18+
- MySQL Server
- Maven

### Database Setup

Ensure MySQL is running on `localhost:3306`. The application will automatically create the required databases (`ecommerce_auth`, `ecommerce_product`, `ecommerce_order`) on startup.

### Environment Configuration

For security reasons, sensitive configurations (like passwords and secret keys) are NOT pushed to the repository. You must create a `.env` file in the root directory with the following structure:

```env
# Database Configuration
DB_PASSWORD=your_mysql_password

# JWT Security
JWT_SECRET=your_super_secret_jwt_key_at_least_32_chars

# SMTP / Email Configuration
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your_email@gmail.com
SMTP_PASSWORD=your_app_password
MAIL_FROM=your_email@gmail.com
SUPPORT_EMAIL=support@mystore.local

# Razorpay Configuration
RAZORPAY_KEY_ID=your_razorpay_key
RAZORPAY_KEY_SECRET=your_razorpay_secret
RAZORPAY_CURRENCY=INR
RAZORPAY_MOCK=false
```

### Running the Backend

You can run the microservices using Maven. Start them in the following order:
1. Discovery Service
2. Gateway Service
3. Auth Service, Product Service, Order Service

To start a service:
```bash
cd <service-name>
mvn spring-boot:run
```

*(Note: The services are configured to automatically load the `.env` file from the root directory).*

### Running the Frontend

Navigate to the `frontend` directory and start the Vite development server:
```bash
cd frontend
npm install
npm run dev
```

## License

This project is licensed under the MIT License.
