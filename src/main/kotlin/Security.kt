package com.example

import at.favre.lib.crypto.bcrypt.BCrypt

// Хеширование пароля
fun hashPassword(password: String): String {
    return BCrypt.withDefaults().hashToString(12, password.toCharArray())
}

// Проверка пароля
fun verifyPassword(password: String, hash: String): Boolean {
    return BCrypt.verifyer().verify(password.toCharArray(), hash).verified
}