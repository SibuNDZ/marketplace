# smoke-test.ps1 — end-to-end MVP smoke against a locally running app.
# Prereq: .\mvnw spring-boot:run (dev profile) in another terminal.
# Exercises: register vendor -> create product -> register customer ->
# login -> /me -> add to cart -> checkout -> verify snapshot -> cancel ->
# verify stock restored. Any non-2xx (except the deliberate 401) throws.

$base = "http://localhost:8080/api/v1"
$stamp = Get-Date -Format "HHmmss"

function Post($url, $body, $token) {
    $headers = @{}
    if ($token) { $headers["Authorization"] = "Bearer $token" }
    Invoke-RestMethod -Uri $url -Method Post -ContentType "application/json" `
        -Body ($body | ConvertTo-Json) -Headers $headers
}
function Get2($url, $token) {
    Invoke-RestMethod -Uri $url -Headers @{ Authorization = "Bearer $token" }
}

Write-Host "1) Register vendor..."
$vendor = Post "$base/auth/register" @{
    email = "vendor$stamp@test.local"; password = "password123"
    fullName = "Smoke Vendor"; role = "VENDOR"
}
Write-Host "   vendor userId=$($vendor.userId) role=$($vendor.role)"

Write-Host "2) Vendor creates a product (stock=5)..."
$product = Post "$base/products" @{
    name = "Smoke Widget $stamp"; description = "smoke test"
    sku = "SMK-$stamp"; price = 199.99; stock = 5
} $vendor.accessToken
Write-Host "   productId=$($product.id) vendor=$($product.vendorName)"

Write-Host "3) Register customer + /me sanity..."
$cust = Post "$base/auth/register" @{
    email = "cust$stamp@test.local"; password = "password123"
    fullName = "Smoke Customer"; role = "CUSTOMER"
}
$me = Get2 "$base/auth/me" $cust.accessToken
if ($me.userId -ne $cust.userId) { throw "/me returned wrong user!" }
Write-Host "   /me OK: $($me.email) ($($me.role))"

Write-Host "4) Unauthenticated cart access must be 401..."
try {
    Invoke-RestMethod -Uri "$base/cart" | Out-Null
    throw "SECURITY HOLE: cart readable without token"
} catch {
    if ($_.Exception.Response.StatusCode.value__ -eq 401) { Write-Host "   401 OK" }
    else { throw }
}

Write-Host "5) Add 2 units to cart..."
$cart = Post "$base/cart/items" @{ productId = $product.id; quantity = 2 } $cust.accessToken
Write-Host "   subtotal=$($cart.subtotal) (expect 399.98)"

Write-Host "6) Checkout..."
$order = Post "$base/orders" $null $cust.accessToken
Write-Host "   orderId=$($order.id) status=$($order.status) total=$($order.total)"

Write-Host "7) Verify stock decremented and cart cleared..."
$p = Invoke-RestMethod -Uri "$base/products/$($product.id)"
$cartAfter = Get2 "$base/cart" $cust.accessToken
Write-Host "   stock=$($p.stock) (expect 3), cart items=$($cartAfter.items.Count) (expect 0)"

Write-Host "8) Vendor doubles the price; order total must NOT move..."
Invoke-RestMethod -Uri "$base/products/$($product.id)" -Method Put `
    -ContentType "application/json" -Headers @{ Authorization = "Bearer $($vendor.accessToken)" } `
    -Body (@{ name = $p.name; description = $p.description; sku = $p.sku
              price = 399.98; stock = $p.stock } | ConvertTo-Json) | Out-Null
$orderAfter = Get2 "$base/orders/$($order.id)" $cust.accessToken
Write-Host "   order total=$($orderAfter.total) (expect 399.98 from ORIGINAL price 2x199.99)"

Write-Host "9) Cancel order; stock must restore..."
Post "$base/orders/$($order.id)/cancel" $null $cust.accessToken | Out-Null
$pFinal = Invoke-RestMethod -Uri "$base/products/$($product.id)"
Write-Host "   status after cancel: stock=$($pFinal.stock) (expect 5)"

Write-Host ""
Write-Host "SMOKE TEST COMPLETE - verify the expected values above." -ForegroundColor Green
