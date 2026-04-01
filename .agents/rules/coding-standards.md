# Rule: Coding Standards
# Always On — applies to every code generation task

## Java
- Always use Java 21 features (records, sealed classes, pattern matching where appropriate)
- Always use Spring Boot 3.x conventions
- Always annotate services with @Service, repositories with @Repository, controllers with @RestController
- Always use constructor injection (never @Autowired on fields)
- Always add @Slf4j for logging — use log.info(), log.warn(), log.error() (never System.out.println)
- Always use Optional<> for nullable repository returns
- Always use BigDecimal for monetary amounts (never double or float)
- Always use UUID for primary keys
- Always use LocalDateTime for timestamps (never Date or Calendar)
- Never use wildcard imports (e.g. import java.util.*)
- Never use raw types — always use generics

## Package Structure
- All classes go under com.autoinvoice.*
- Controllers → com.autoinvoice.{feature}
- Services → com.autoinvoice.{feature}
- Repositories → com.autoinvoice.{feature}
- DTOs/Records → com.autoinvoice.{feature} (suffix with DTO, Request, Response)
- Entities → com.autoinvoice.{feature} (no suffix)
- Enums → com.autoinvoice.{feature}

## REST API
- Always return ResponseEntity<> from controllers
- Always use proper HTTP status codes: 200 OK, 201 Created, 400 Bad Request, 401 Unauthorized, 404 Not Found, 502 Bad Gateway
- Always version-prefix routes if adding new endpoints beyond the original 15: /api/v2/...
- Always validate request bodies with @Valid and Jakarta Bean Validation annotations
- Always include userId from X-User-Id request header (never hardcode user IDs)

## Error Handling
- Always throw custom exceptions (TokenNotFoundException, VaultException, InvoiceNotFoundException)
- Always let GlobalExceptionHandler catch and format errors — never return raw error strings from controllers
- Always log the full stack trace at error level before rethrowing

## Database
- Always use snake_case for column names and table names
- Always add createdAt and updatedAt timestamps to every entity
- Always define indexes on foreign keys and frequently queried columns
- Never use ddl-auto: create-drop in any profile (use update for dev, validate for prod)

## Testing
- Name test methods: methodName_whenCondition_thenExpectedResult
- Always mock external services (Gmail, Paystack, OpenAI API) in unit tests
- Never make real HTTP calls in unit tests
