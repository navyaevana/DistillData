# Data Processing Application

A full-stack web application for processing CSV and Excel datasets. The application cleans data (removes null values and duplicates), analyzes it, and returns the processed file.

## Features

- **File Upload**: Accept CSV and Excel files
- **Data Cleaning**:
  - Remove rows with all null/empty values
  - Remove duplicate rows
- **Data Analysis**: Basic statistical analysis including column types, counts, and numeric statistics
- **File Download**: Download processed files
- **Metadata Storage**: Store processing results in database

## Tech Stack

- **Frontend**: React + TypeScript + Vite
- **Backend**: Spring Boot + Java
- **Database**: H2 (in-memory for demo, can be switched to MySQL)
- **Libraries**:
  - Apache POI for Excel processing
  - OpenCSV for CSV processing
  - Axios for HTTP requests

## Project Structure

```
distilldata/
├── demo/                    # Spring Boot backend
│   ├── src/main/java/com/distilldata/demo/
│   │   ├── controller/      # REST controllers
│   │   ├── entity/          # JPA entities
│   │   ├── repository/      # Data repositories
│   │   └── service/         # Business logic
│   └── src/main/resources/
│       └── application.properties
└── frontend/                # React frontend
    ├── src/
    │   ├── App.tsx          # Main component
    │   ├── App.css          # Styles
    │   └── main.tsx         # Entry point
    └── package.json
```

## Setup and Run

### Prerequisites
- Java 17+ (or update build.gradle for your version)
- Node.js 16+
- Gradle (or use wrapper)

### Backend Setup
1. Navigate to `demo/` directory
2. Build the project: `./gradlew build` (or `gradlew.bat build` on Windows)
3. Run the application: `./gradlew bootRun` (or `gradlew.bat bootRun`)

The backend will start on `http://localhost:8081`

### Frontend Setup
1. Navigate to `frontend/` directory
2. Install dependencies: `npm install`
3. Start development server: `npm run dev`

The frontend will start on `http://localhost:5173` (or next available port)

### Database
The application uses H2 in-memory database by default. For production, update `application.properties` to use MySQL:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/distilldata_db
spring.datasource.username=your_username
spring.datasource.password=your_password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQL8Dialect
```

And update `build.gradle` dependencies accordingly.

## API Endpoints

- `POST /api/data/upload` - Upload and process file
- `GET /api/data/datasets` - Get all processed datasets
- `GET /api/data/download/{id}` - Download processed file

## Usage

1. Open the frontend in your browser
2. Click "Choose File" to select a CSV or Excel file
3. Click "Upload & Process" to clean and analyze the data
4. View the processed datasets in the list
5. Click "View Analysis" to see analysis results
6. Click "Download Processed" to download the cleaned file

## File Processing Details

### Cleaning
- Removes rows where all cells are null or empty
- Removes exact duplicate rows

### Analysis
- Total rows and columns
- Per-column analysis:
  - Data type detection
  - Null/non-null counts
  - For numeric columns: min, max, average

### Output
- Processed file saved in `uploads/` directory
- Metadata stored in database
- Analysis results as JSON string