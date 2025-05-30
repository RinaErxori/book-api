package com.example

import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID

fun Application.configureRouting() {
    val uploadDir = File("uploads")
    if (!uploadDir.exists()) {
        uploadDir.mkdirs()
    }

    routing {
        get("/") {
            call.respondText("Hello World!")
        }

        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }

        post("/upload") {
            val multipart = call.receiveMultipart()
            var fileUrl: String? = null

            multipart.forEachPart { part ->
                if (part is PartData.FileItem) {
                    val fileName = part.originalFileName ?: "image-${UUID.randomUUID()}.jpg"
                    val file = File(uploadDir, fileName)
                    part.streamProvider().use { input ->
                        file.outputStream().buffered().use { output ->
                            input.copyTo(output)
                        }
                    }
                    fileUrl = "/uploads/$fileName"
                }
                part.dispose()
            }

            if (fileUrl != null) {
                call.respond(mapOf("imageUrl" to fileUrl!!))
            } else {
                call.respondText("Failed to upload image", status = HttpStatusCode.BadRequest)
            }
        }

        get("/books") {
            val books = transaction {
                BookCard.selectAll().map {
                    Book(
                        title = it[BookCard.title],
                        author = it[BookCard.author],
                        description = it[BookCard.description] ?: "",
                        price = it[BookCard.price],
                        imageId = it[BookCard.imageId]
                    )
                }
            }
            call.respond(books)
        }

        get("/api/book/{title}") {
            val encodedTitle = call.parameters["title"]
            if (encodedTitle != null) {
                val title = URLDecoder.decode(encodedTitle, StandardCharsets.UTF_8.toString()).trim()
                val book = transaction {
                    BookCard.select { BookCard.title eq title }.singleOrNull()?.let {
                        Book(
                            title = it[BookCard.title],
                            author = it[BookCard.author],
                            description = it[BookCard.description] ?: "",
                            price = it[BookCard.price],
                            imageId = it[BookCard.imageId]
                        )
                    }
                }
                if (book != null) {
                    call.respond(book)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Book not found")
                }
            } else {
                call.respond(HttpStatusCode.BadRequest, "Title parameter is missing")
            }
        }

        post("/purchase") {
            val userId = call.request.headers["User-Id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("User-Id header is missing or invalid", status = HttpStatusCode.BadRequest)
                return@post
            }

            val purchaseRequest = call.receive<PurchaseRequest>()
            val bookTitle = purchaseRequest.bookTitle

            val bookExists = transaction {
                BookCard.select { BookCard.title eq bookTitle }.count() > 0
            }

            if (!bookExists) {
                call.respondText("Book not found", status = HttpStatusCode.NotFound)
                return@post
            }

            val alreadyPurchased = transaction {
                PurchasedBooks.select {
                    (PurchasedBooks.userId eq userId) and (PurchasedBooks.bookTitle eq bookTitle)
                }.count() > 0
            }

            if (alreadyPurchased) {
                call.respondText("Book already purchased", status = HttpStatusCode.Conflict)
                return@post
            }

            transaction {
                PurchasedBooks.insert {
                    it[PurchasedBooks.userId] = userId
                    it[PurchasedBooks.bookTitle] = bookTitle
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Book purchased successfully"))
        }

        get("/purchased-books") {
            val userId = call.request.headers["User-Id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("User-Id header is missing or invalid", status = HttpStatusCode.BadRequest)
                return@get
            }

            val purchasedBooks = transaction {
                PurchasedBooks.select { PurchasedBooks.userId eq userId }
                    .mapNotNull { purchased ->
                        BookCard.select { BookCard.title eq purchased[PurchasedBooks.bookTitle] }
                            .singleOrNull()?.let { bookCard ->
                                PurchasedBook(
                                    title = bookCard[BookCard.title],
                                    author = bookCard[BookCard.author],
                                    description = bookCard[BookCard.description] ?: "",
                                    price = bookCard[BookCard.price],
                                    imageId = bookCard[BookCard.imageId]
                                )
                            }
                    }
            }

            call.respond(purchasedBooks)
        }

        post("/register") {
            val request = call.receive<RegisterRequest>()
            val email = request.email
            val password = request.password
            val username = request.username

            val existingUser = transaction {
                Users.select { Users.email eq email }.firstOrNull()
            }

            if (existingUser != null) {
                call.respondText("User with this email already exists", status = HttpStatusCode.Conflict)
                return@post
            }

            val passwordHash = hashPassword(password)

            val newUserId = transaction {
                Users.insert {
                    it[Users.email] = email
                    it[Users.passwordHash] = passwordHash
                    it[Users.username] = username
                }[Users.id]
            }

            val newUser = User(
                id = newUserId,
                email = email,
                username = username
            )
            call.respond(newUser)
        }

        post("/login") {
            val request = call.receive<LoginRequest>()
            val email = request.email
            val password = request.password

            val userRecord = transaction {
                Users.select { Users.email eq email }.firstOrNull()
            }

            if (userRecord == null) {
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@post
            }

            val passwordHash = userRecord[Users.passwordHash]
            if (!verifyPassword(password, passwordHash)) {
                call.respondText("Invalid password", status = HttpStatusCode.Unauthorized)
                return@post
            }

            val user = User(
                id = userRecord[Users.id],
                email = userRecord[Users.email],
                username = userRecord[Users.username]
            )
            val response = LoginResponse(
                user = user,
                token = "fake-token-${user.id}"
            )
            call.respond(response)
        }

        get("/user") {
            val userId = call.request.headers["User-Id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("User-Id header is missing or invalid", status = HttpStatusCode.BadRequest)
                return@get
            }

            val userRecord = transaction {
                Users.select { Users.id eq userId }.firstOrNull()
            }

            if (userRecord == null) {
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@get
            }

            val user = User(
                id = userRecord[Users.id],
                email = userRecord[Users.email],
                username = userRecord[Users.username]
            )
            call.respond(user)
        }

        put("/user") {
            val userId = call.request.headers["User-Id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("User-Id header is missing or invalid", status = HttpStatusCode.BadRequest)
                return@put
            }

            val updateRequest = call.receive<User>()
            if (updateRequest.id != userId) {
                call.respondText("User ID mismatch", status = HttpStatusCode.BadRequest)
                return@put
            }

            val userExists = transaction {
                Users.select { Users.id eq userId }.firstOrNull() != null
            }

            if (!userExists) {
                call.respondText("User not found", status = HttpStatusCode.NotFound)
                return@put
            }

            val emailConflict = transaction {
                Users.select { (Users.email eq updateRequest.email) and (Users.id neq userId) }.firstOrNull()
            }

            if (emailConflict != null) {
                call.respondText("Email is already in use by another user", status = HttpStatusCode.Conflict)
                return@put
            }

            transaction {
                Users.update({ Users.id eq userId }) {
                    it[email] = updateRequest.email
                    it[username] = updateRequest.username
                }
            }

            call.respond(updateRequest)
        }

        post("/reviews") {
            val userId = call.request.headers["User-Id"]?.toIntOrNull()
            if (userId == null) {
                call.respondText("User-Id header is missing or invalid", status = HttpStatusCode.BadRequest)
                return@post
            }

            val reviewRequest = call.receive<ReviewRequest>()
            val bookId = reviewRequest.bookId
            val rating = reviewRequest.rating
            val comment = reviewRequest.comment

            if (rating < 1 || rating > 5) {
                call.respondText("Rating must be between 1 and 5", status = HttpStatusCode.BadRequest)
                return@post
            }

            val bookExists = transaction {
                BookCard.select { BookCard.id eq bookId }.count() > 0
            }

            if (!bookExists) {
                call.respondText("Book not found", status = HttpStatusCode.NotFound)
                return@post
            }

            val existingReview = transaction {
                BookReviews.select { (BookReviews.bookId eq bookId) and (BookReviews.userId eq userId) }.count() > 0
            }

            if (existingReview) {
                call.respondText("User already reviewed this book", status = HttpStatusCode.Conflict)
                return@post
            }

            transaction {
                BookReviews.insert {
                    it[BookReviews.bookId] = bookId
                    it[BookReviews.userId] = userId
                    it[BookReviews.rating] = rating
                    it[BookReviews.comment] = comment
                }
            }

            call.respond(HttpStatusCode.OK, mapOf("message" to "Review added successfully"))
        }

        get("/reviews/{bookId}") {
            val bookId = call.parameters["bookId"]?.toIntOrNull()
            if (bookId == null) {
                call.respondText("Invalid book ID", status = HttpStatusCode.BadRequest)
                return@get
            }

            val reviews = transaction {
                BookReviews.select { BookReviews.bookId eq bookId }.map { review ->
                    Review(
                        id = review[BookReviews.id],
                        bookId = review[BookReviews.bookId],
                        userId = review[BookReviews.userId],
                        username = Users.select { Users.id eq review[BookReviews.userId] }
                            .single()[Users.username],
                        rating = review[BookReviews.rating],
                        comment = review[BookReviews.comment] ?: ""
                    )
                }
            }

            call.respond(reviews)
        }
    }
}

@Serializable
data class Book(
    val title: String,
    val author: String,
    val description: String,
    val price: String,
    val imageId: Int
)

@Serializable
data class PurchasedBook(
    val title: String,
    val author: String,
    val description: String,
    val price: String,
    val imageId: Int
)

@Serializable
data class User(
    val id: Int,
    val email: String,
    val username: String
)

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val username: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val user: User,
    val token: String
)

@Serializable
data class PurchaseRequest(
    val bookTitle: String
)

@Serializable
data class ReviewRequest(
    val bookId: Int,
    val rating: Int,
    val comment: String? = null
)

@Serializable
data class Review(
    val id: Int,
    val bookId: Int,
    val userId: Int,
    val username: String,
    val rating: Int,
    val comment: String
)