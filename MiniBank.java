import java.util.Scanner;

public class MiniBank {
    record BankInfo(String name, String branch) {}
    enum MenuOption { OPEN_ACCOUNT, DEPOSIT, WITHDRAW, TRANSFER, EXIT; }

    public static void main(String[] args) {
        BankInfo bi = new BankInfo("MiniBank", "Main Branch");
        System.out.println("Welcome to " + bi.name() + " - " + bi.branch());

        Account[] accounts = new Account[3];
        accounts[0] = new Account("Alice", 1000);
        accounts[1] = new Account("Bob", 500);
        accounts[2] = new Account("Charlie");

        accounts[0].deposit(500);
        accounts[1].withdraw(200);
        accounts[2].deposit(1000);

        for (Account acc : accounts) {
            System.out.println(acc.getAccountNumber() + " | " + acc.getOwnerName() + " | Balance: " + acc.getBalance());
        }
    }
}

class Customer {
    private String name;
    private String email;
    private String mobile;
    private final String customerId;
    private static long customerCounter = 100;

    public Customer(String name, String email, String mobile) {
        this.name = name;
        this.email = email;
        this.mobile = mobile;
        this.customerId = generateCustomerId();
    }

    private static String generateCustomerId() {
        return "CUST" + (++customerCounter);
    }

    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getMobile() { return mobile; }
    public String getCustomerId() { return customerId; }
}

class Account {
    private final String accountNumber;
    private String ownerName;
    private long balance;
    private boolean active;
    private static int counter = 0;

    public Account(String ownerName, long openingBalance) {
        this.accountNumber = "AC" + String.format("%04d", ++counter);
        this.ownerName = ownerName;
        this.balance = openingBalance;
        this.active = true;
    }

    public Account(String ownerName) {
        this(ownerName, 0);
    }

    public void deposit(long amount) { balance += amount; }
    public boolean withdraw(long amount) {
        if (balance >= amount) {
            balance -= amount;
            return true;
        }
        return false;
    }

    public String getAccountNumber() { return accountNumber; }
    public String getOwnerName() { return ownerName; }
    public long getBalance() { return balance; }
}
