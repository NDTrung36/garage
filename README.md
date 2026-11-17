# Garage sửa xe — MVP

Hệ thống quản lý một garage ô tô và xe máy: đặt lịch không cần tài khoản, tiếp nhận và sửa chữa, phụ tùng, hóa đơn và trạng thái thanh toán.

## Công nghệ

- Backend: Java 21, Spring Boot 3.5.16, Maven, Spring Security, Spring Data JPA.
- Database: SQL Server 2022 trở lên; Flyway quản lý migration T-SQL.
- Frontend: React, TypeScript, Vite.

## Trạng thái hiện tại

Đã tạo khung dự án, tài liệu thiết kế và 3 migration Flyway cho 11 bảng nền phục vụ Guest Booking. Chưa triển khai API nghiệp vụ, giao diện chức năng hoặc cấu hình phân quyền. Các bảng sửa chữa, kho và hóa đơn vẫn là thiết kế cho giai đoạn sau.

Backend có dependency Spring Security nên cấu hình mặc định của Spring vẫn bảo vệ các endpoint. Mô hình Guest Booking sẽ được cấu hình khi triển khai API public; chưa có tài khoản Admin/Staff được tạo sẵn.

## Cấu trúc

```text
garage-sua-xe/
├── backend/                         # Một ứng dụng Maven, module theo package
│   ├── pom.xml
│   └── src/
│       ├── main/
│       │   ├── java/vn/garage/
│       │   │   ├── GarageApplication.java
│       │   │   ├── identity/        # api | application | domain | infrastructure
│       │   │   ├── customer/        # api | application | domain | infrastructure
│       │   │   ├── catalog/         # api | application | domain | infrastructure
│       │   │   ├── repair/          # api | application | domain | infrastructure
│       │   │   ├── billing/         # api | application | domain | infrastructure
│       │   │   └── inventory/       # api | application | domain | infrastructure
│       │   └── resources/
│       │       ├── application.yml
│       │       └── db/migration/   # Flyway
│       └── test/java/vn/garage/
├── frontend/
│   └── src/
│       ├── api/                     # Hợp đồng và cấu hình HTTP dùng chung
│       ├── app/                     # Điểm vào ứng dụng
│       └── features/
│           ├── auth/
│           ├── booking/
│           ├── lookup/
│           ├── customer/
│           ├── vehicle/
│           ├── service/
│           ├── repair/
│           ├── part/
│           ├── inventory/
│           ├── invoice/
│           └── settings/
└── docs/
    ├── requirements.md              # Đặc tả và tiêu chí nghiệm thu
    ├── erd.md                       # ERD và từ điển dữ liệu SQL Server
    └── architecture.md              # Kiến trúc, luồng xử lý, API dự kiến
```

## Tài liệu

1. [Đặc tả nghiệp vụ](docs/requirements.md).
2. [ERD và từ điển dữ liệu](docs/erd.md).
3. [Kiến trúc hệ thống](docs/architecture.md).
4. [Hướng dẫn backend](backend/README.md).
5. [Hướng dẫn frontend](frontend/README.md).
6. [Migration nền và bằng chứng kiểm thử](docs/migration-foundation.md).

## Khởi động

Frontend:

```powershell
cd frontend
npm install
npm run dev
```

Backend cần SQL Server có TCP/JDBC và biến môi trường kết nối trước khi chạy; xem [hướng dẫn backend](backend/README.md). Khi kết nối đúng database đã tạo riêng, Flyway áp dụng schema nền và seed cấu hình garage; chưa có API để vận hành nghiệp vụ.
