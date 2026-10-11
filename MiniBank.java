import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import static java.lang.Math.max;

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface Id {}

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface Positive {
    String message() default "must be > 0";
}

@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface MaxLength {
    int value();
}

class BankException extends Exception {
    public BankException(String message) {
        super(message);
    }
}

class InsufficientFundsException extends BankException {
    private final long shortfall;

    public InsufficientFundsException(String message, long shortfall) {
        super(message);
        this.shortfall = shortfall;
    }

    public long getShortfall() {
        return shortfall;
    }
}

class AccountNotFoundException extends BankException {
    public AccountNotFoundException(String message) {
        super(message);
    }
}

class InvalidAmountException extends BankException {
    public InvalidAmountException(String message) {
        super(message);
    }
}

class BankAuditSession implements AutoCloseable {
    private final String sessionId;

    public BankAuditSession(String sessionId) {
        this.sessionId = sessionId;
        System.out.println("Audit session opened: " + sessionId);
    }

    @Override
    public void close() {
        System.out.println("Audit session closed safely: " + sessionId);
    }
}

class AccountWorker implements Runnable {
    private Account account;
    private int times;
    private long amount;

    public AccountWorker(Account account, int times, long amount) {
        this.account = account;
        this.times = times;
        this.amount = amount;
    }

    @Override
    public void run() {
        for (int i = 0; i < times; i++) {
            try {
                account.deposit(amount);
            } catch (InvalidAmountException e) {
            }
        }
    }
}

class TransactionProcessor {
    private final ExecutorService executor;

    public TransactionProcessor(int poolSize) {
        this.executor = Executors.newFixedThreadPool(poolSize);
    }

    public void submit(Runnable task) {
        executor.execute(task);
    }

    public void stop() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }
}

class TransactionBuffer {
    private final Queue<String> queue = new LinkedList<>();
    private final int capacity;

    public TransactionBuffer(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void produce(String tx) throws InterruptedException {
        while (queue.size() == capacity) {
            wait();
        }
        queue.add(tx);
        System.out.println("Produced transaction: " + tx);
        notifyAll();
    }

    public synchronized String consume() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        String tx = queue.poll();
        System.out.println("Consumed transaction: " + tx);
        notifyAll();
        return tx;
    }
}

record BankInfo(String name, String branch) {}

enum MenuOption {
    OPEN_ACCOUNT,
    DEPOSIT,
    WITHDRAW,
    TRANSFER,
    EXIT
}

enum TransactionType {
    DEPOSIT,
    WITHDRAW,
    TRANSFER
}

record Command(TransactionType type, String accountNumber, long amount) {}

interface Transactable {
    void deposit(long amount) throws InvalidAmountException;
    boolean withdraw(long amount) throws InsufficientFundsException, InvalidAmountException;
}

interface InterestBearing {
    double interestRate();
    default double yearlyInterest(long balance) {
        return (balance * interestRate()) / 100.0;
    }
}

interface Premium {}

@FunctionalInterface
interface WithdrawRule {
    boolean allow(Account account, long amount);
}

class AnnotationValidator {
    public static String[] validate(Object obj) {
        if (obj == null) return new String[0];
        List<String> errors = new ArrayList<>();
        Class<?> clazz = obj.getClass();

        while (clazz != null && clazz != Object.class) {
            Field[] fields = clazz.getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);

                    if (field.isAnnotationPresent(Positive.class)) {
                        Positive pos = field.getAnnotation(Positive.class);
                        if (val instanceof Number num) {
                            if (num.longValue() <= 0) {
                                errors.add(pos.message());
                            }
                        }
                    }

                    if (field.isAnnotationPresent(MaxLength.class)) {
                        MaxLength ml = field.getAnnotation(MaxLength.class);
                        if (val instanceof String str) {
                            if (str.length() > ml.value()) {
                                errors.add(field.getName() + " length exceeds maximum of " + ml.value());
                            }
                        }
                    }
                } catch (IllegalAccessException e) {
                }
            }
            clazz = clazz.getSuperclass();
        }
        return errors.toArray(new String[0]);
    }
}

class Validator {
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9][0-9]{9}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$");
    private static final Pattern PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
    private static final Pattern IFSC_PATTERN = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    public static boolean isValidMobile(String mobile) {
        return mobile != null && MOBILE_PATTERN.matcher(mobile).matches();
    }

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isValidPan(String pan) {
        return pan != null && PAN_PATTERN.matcher(pan).matches();
    }

    public static boolean isValidIfsc(String ifsc) {
        return ifsc != null && IFSC_PATTERN.matcher(ifsc).matches();
    }
}

class CommandParser {
    public static Command parse(String line) {
        if (line == null) return null;
        String[] parts = line.trim().split("\\s+");
        if (parts.length != 3) {
            return null;
        }

        try {
            TransactionType type = TransactionType.valueOf(parts[0].toUpperCase());
            String accNo = parts[1];
            long amount = Long.parseLong(parts[2]);
            return new Command(type, accNo, amount);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

class StatementFormatter {
    public static String buildStatement(Account account) {
        StringBuilder sb = new StringBuilder();
        sb.append("====================================\n");
        sb.append("         ACCOUNT STATEMENT          \n");
        sb.append("====================================\n");
        sb.append("Account Number : ").append(account.getAccountNumber()).append("\n");
        sb.append("Account Holder : ").append(account.getOwnerName()).append("\n");
        sb.append("Current Balance: Rs. ").append(account.getBalance()).append("\n");
        sb.append("Interest Rate  : ").append(account.interestRate()).append("%\n");
        sb.append("Yearly Interest: Rs. ").append(account.yearlyInterest(account.getBalance())).append("\n");
        sb.append("Account Status : ").append(account.isActive() ? "Active" : "Inactive").append("\n");
        sb.append("====================================");
        return sb.toString();
    }
}

class Customer implements Cloneable {
    @Id
    private final String customerId;
    @MaxLength(50)
    private String name;
    private String email;
    private String mobile;
    private Address address;
    private static long customerCounter = 100;

    public static class Address {
        private String line;
        private String city;
        private String pincode;

        public Address(String line, String city, String pincode) {
            this.line = line;
            this.city = city;
            this.pincode = pincode;
        }

        public String getLine() {
            return line;
        }

        public String getCity() {
            return city;
        }

        public String getPincode() {
            return pincode;
        }

        @Override
        public String toString() {
            return line + ", " + city + " - " + pincode;
        }
    }

    private static String generateCustomerId() {
        customerCounter++;
        return "CUST" + customerCounter;
    }

    public Customer(String name, String email, String mobile, Address address) {
        this.customerId = generateCustomerId();
        this.name = name;
        this.email = email;
        this.mobile = mobile;
        this.address = address;
    }

    public Customer(String name, String email, String mobile) {
        this(name, email, mobile, null);
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getMobile() {
        return mobile;
    }

    public Address getAddress() {
        return address;
    }

    public void setAddress(Address address) {
        this.address = address;
    }

    @Override
    public Object clone() throws CloneNotSupportedException {
        return super.clone();
    }
}

abstract class Account implements Transactable, InterestBearing {
    @Id
    private final String accountNumber;
    @MaxLength(50)
    private String ownerName;
    @Positive
    protected long balance;
    private boolean active;
    private static long accountCounter = 0;

    private static String generateAccountNumber() {
        accountCounter++;
        return String.format("AC%04d", accountCounter);
    }

    public Account(String ownerName, long balance) {
        this.accountNumber = generateAccountNumber();
        this.ownerName = ownerName;
        this.balance = balance;
        this.active = true;
    }

    public Account(String accountNumber, String ownerName, long balance) {
        this.accountNumber = accountNumber;
        this.ownerName = ownerName;
        this.balance = balance;
        this.active = true;
    }

    public Account(String ownerName) {
        this(ownerName, 0);
    }

    @Override
    public abstract double interestRate();
    public abstract boolean canWithdraw(long amount);

    @Override
    public synchronized void deposit(long amount) throws InvalidAmountException {
        if (amount <= 0) {
            throw new InvalidAmountException("Deposit amount must be greater than zero.");
        }
        balance += amount;
    }

    @Override
    public synchronized boolean withdraw(long amount) throws InsufficientFundsException, InvalidAmountException {
        if (amount <= 0) {
            throw new InvalidAmountException("Withdrawal amount must be greater than zero.");
        }
        if (!canWithdraw(amount)) {
            long shortfall = amount - balance;
            throw new InsufficientFundsException("Withdrawal failed: insufficient balance.", max(0, shortfall));
        }
        balance -= amount;
        return true;
    }

    public void transfer(Account to, long amount) throws BankException {
        if (to == null) {
            throw new AccountNotFoundException("Destination account not found.");
        }
        
        Account firstLock = this.accountNumber.compareTo(to.accountNumber) < 0 ? this : to;
        Account secondLock = this.accountNumber.compareTo(to.accountNumber) < 0 ? to : this;

        synchronized (firstLock) {
            synchronized (secondLock) {
                try {
                    this.withdraw(amount);
                    to.deposit(amount);
                } catch (InsufficientFundsException | InvalidAmountException e) {
                    throw e;
                }
            }
        }
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public synchronized long getBalance() {
        return balance;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public String toString() {
        return accountNumber + " | " + ownerName + " | " + balance;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Account account = (Account) o;
        return Objects.equals(accountNumber, account.accountNumber);
    }

    @Override
    public int hashCode() {
        return Objects.hash(accountNumber);
    }
}

class SavingsAccount extends Account {
    private long minBalance;

    public SavingsAccount(String ownerName, long balance, long minBalance) {
        super(ownerName, balance);
        this.minBalance = minBalance;
    }

    public SavingsAccount(String ownerName, long balance) {
        this(ownerName, balance, 1000);
    }

    @Override
    public double interestRate() {
        return 4.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return (balance - amount) >= minBalance;
    }

    public long getMinBalance() {
        return minBalance;
    }
}

class CurrentAccount extends Account implements Premium {
    private long overdraftLimit;

    public CurrentAccount(String ownerName, long balance, long overdraftLimit) {
        super(ownerName, balance);
        this.overdraftLimit = overdraftLimit;
    }

    public CurrentAccount(String ownerName, long balance) {
        this(ownerName, balance, 5000);
    }

    @Override
    public double interestRate() {
        return 0.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return (balance - amount) >= -overdraftLimit;
    }

    public long getOverdraftLimit() {
        return overdraftLimit;
    }
}

class FixedDepositAccount extends Account {
    public FixedDepositAccount(String ownerName, long balance) {
        super(ownerName, balance);
    }

    @Override
    public double interestRate() {
        return 7.0;
    }

    @Override
    public boolean canWithdraw(long amount) {
        return false;
    }
}

public class MiniBank {
    public static void printMenu() {
        System.out.println("\n--- MiniBank Menu ---");
        System.out.println("1. Open Account");
        System.out.println("2. Deposit");
        System.out.println("3. Withdraw");
        System.out.println("4. Transfer");
        System.out.println("5. Exit");
        System.out.print("Choose an option: ");
    }

    public static void main(String[] args) {
        BankInfo info = new BankInfo("State Bank", "Main Branch");
        System.out.println(info);

        SavingsAccount poolAcc = new SavingsAccount("PoolTest", 1000, 0);
        TransactionProcessor tp = new TransactionProcessor(4);

        System.out.println("\n--- Running Thread Pool Transactions ---");
        for (int i = 0; i < 8; i++) {
            final int id = i + 1;
            tp.submit(() -> {
                try {
                    poolAcc.deposit(100);
                    System.out.println("Task " + id + " deposited 100 by " + Thread.currentThread().getName());
                } catch (InvalidAmountException e) {
                }
            });
        }
        tp.stop();
        System.out.println("Balance after pool tasks: Rs. " + poolAcc.getBalance());

        System.out.println("\n--- Running Producer-Consumer Queue ---");
        TransactionBuffer buffer = new TransactionBuffer(2);
        Thread producer = new Thread(() -> {
            try {
                buffer.produce("TX_DEP_100");
                buffer.produce("TX_WDR_50");
                buffer.produce("TX_TRF_30");
            } catch (InterruptedException e) {
            }
        });

        Thread consumer = new Thread(() -> {
            try {
                for (int i = 0; i < 3; i++) {
                    buffer.consume();
                }
            } catch (InterruptedException e) {
            }
        });

        producer.start();
        consumer.start();

        try {
            producer.join();
            consumer.join();
        } catch (InterruptedException e) {
        }

        System.out.println("\n--- Testing Deadlock-Free Transfer ---");
        SavingsAccount a = new SavingsAccount("Alice", 5000, 0);
        SavingsAccount b = new SavingsAccount("Bob", 5000, 0);

        Thread t1 = new Thread(() -> {
            try {
                a.transfer(b, 500);
            } catch (BankException e) {
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                b.transfer(a, 300);
            } catch (BankException e) {
            }
        });

        t1.start();
        t2.start();

        try {
            t1.join();
            t2.join();
        } catch (InterruptedException e) {
        }

        System.out.println("Transfer complete without deadlock. Balances: Alice=" + a.getBalance() + ", Bob=" + b.getBalance());

        Scanner sc = new Scanner(System.in);
        boolean loop = true;

        while (loop) {
            printMenu();
            if (!sc.hasNextInt()) {
                System.out.println("Please enter a valid number.");
                sc.next();
                continue;
            }

            int ch = sc.nextInt();

            MenuOption opt = switch (ch) {
                case 1 -> MenuOption.OPEN_ACCOUNT;
                case 2 -> MenuOption.DEPOSIT;
                case 3 -> MenuOption.WITHDRAW;
                case 4 -> MenuOption.TRANSFER;
                case 5 -> MenuOption.EXIT;
                default -> null;
            };

            if (opt == null) {
                System.out.println("Invalid option, try again.");
                continue;
            }

            switch (opt) {
                case OPEN_ACCOUNT -> System.out.println("Open Account - to be implemented in a later lab");
                case DEPOSIT -> System.out.println("Deposit - to be implemented in a later lab");
                case WITHDRAW -> System.out.println("Withdraw - to be implemented in a later lab");
                case TRANSFER -> System.out.println("Transfer - to be implemented in a later lab");
                case EXIT -> {
                    System.out.println("Exiting MiniBank. Thank you!");
                    loop = false;
                }
            }
        }
        sc.close();
    }
}