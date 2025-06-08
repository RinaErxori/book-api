package com.example

import io.ktor.server.application.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

object Users : Table() {
    val id = integer("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val username = varchar("username", 255)

    override val primaryKey = PrimaryKey(id)
}

object BookCard : Table() {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 255)
    val author = varchar("author", 255)
    val description = text("description").nullable()
    val price = varchar("price", 50)
    val imageId = integer("image_id")

    override val primaryKey = PrimaryKey(id)
}

object PurchasedBooks : Table() {
    val id = integer("id").autoIncrement()
    val userId = integer("user_id").references(Users.id)
    val bookTitle = varchar("book_title", 255)

    override val primaryKey = PrimaryKey(id)
}

object BookReviews : Table() {
    val id = integer("id").autoIncrement()
    val bookId = integer("book_id").references(BookCard.id)
    val userId = integer("user_id").references(Users.id)
    val rating = integer("rating")
    val comment = text("comment").nullable()

    override val primaryKey = PrimaryKey(id)
}

fun Application.configureDatabase() {
    val dbFile = File("app.db")
    println("Database file path: ${dbFile.absolutePath}")

    Database.connect(
        url = "jdbc:sqlite:${dbFile.absolutePath}",
        driver = "org.sqlite.JDBC"
    )

    transaction {
        SchemaUtils.create(Users, BookCard, PurchasedBooks, BookReviews)
        println("Tables 'Users', 'BookCard', 'PurchasedBooks', and 'BookReviews' created or already exist")

        if (BookCard.selectAll().empty()) {
            BookCard.insert {
                it[title] = "The Sixth Child"
                it[author] = "Manith J."
                it[price] = "$15.00"
                it[imageId] = 1
                it[description] = "Begin with eight Sisters..."
            }
            BookCard.insert {
                it[title] = "The Book of God"
                it[author] = "Walter Wangerin"
                it[price] = "$16.88"
                it[imageId] = 2
                it[description] = "... a feat of imagination and faith."
            }
            BookCard.insert {
                it[title] = "The Hobbit"
                it[author] = "J.R.R. Tolkien"
                it[price] = "$12.99"
                it[imageId] = 3
                it[description] = "A classic fantasy novel..."
            }
        }

        if (BookReviews.selectAll().empty()) {
            val bookId = BookCard.select { BookCard.title eq "The Hobbit" }.first()[BookCard.id]
            val userId = Users.selectAll().firstOrNull()?.get(Users.id) ?: 1
            BookReviews.insert {
                it[BookReviews.bookId] = bookId
                it[BookReviews.userId] = userId
                it[rating] = 5
                it[comment] = "Amazing book, a must-read!"
            }
        }
    }
}