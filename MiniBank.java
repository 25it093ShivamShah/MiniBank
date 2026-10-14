import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
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

class StatePersister {
    public static void save(Account[] accounts, Path file) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file.toFile()))) {
                oos.writeObject(accounts);
                System.out.println("Accounts successfully saved to " + file);
            }
        } catch (Exception e) {
            System.out.println("Error saving state: " + e.getMessage());
        }
    }

    public static Account[] load(Path file) {
        if (!Files.exists(file)) {
            return new Account[0];
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file.toFile()))) {
            return (Account[]) ois.readObject();
        } catch (Exception e) {
            System.out.println("Error loading state: " + e.getMessage());
            return new Account[0];
        }
    }
}

class TransactionLog {
    public static void append(Path file, String line) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, (line + System.lineSeparator()).getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.out.println("Error appending to log: " + e.getMessage());
        }
    }
}

class ReportGenerator {
    public static void generateReport(Path logsDir, Path reportFile) {
        long totalDeposits = 0;
        long totalWithdrawals = 0;

        try {
            if (reportFile.getParent() != null) {
                Files.createDirectories(reportFile.getParent());
            }

            if (Files.exists(logsDir)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir)) {
                    for (Path entry : stream) {
                        if (!Files.isDirectory(entry)) {
                            BasicFileAttributes attr = Files.readAttributes(entry, BasicFileAttributes.class);
                            System.out.println("Reading log: " + entry.getFileName() + " | Size: " + attr.size() + " bytes");

                            try (BufferedReader reader = Files.newBufferedReader(entry)) {
                                String line;
                                while ((line = reader.readLine()) != null) {
                                    String[] parts = line.trim().split("\\s+");
                                    if (parts.length >= 3) {
                                        try {
                                            long amt = Long.parseLong(parts[2]);
                                            if (parts[0].equalsIgnoreCase("DEPOSIT")) {
                                                totalDeposits += amt;
                                            } else if (parts[0].equalsIgnoreCase("WITHDRAW")) {
                                                totalWithdrawals += amt;
                                            }
                                        } catch (NumberFormatException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("====================================\n");
            sb.append("         END-OF-DAY REPORT          \n");
            sb.append("====================================\n");
            sb.append("Total Deposits    : Rs. ").append(totalDeposits).append("\n");
            sb.append("Total Withdrawals : Rs. ").append(totalWithdrawals).append("\n");
            sb.append("Net Cashflow      : Rs. ").append(totalDeposits - totalWithdrawals).append("\n");
            sb.append("====================================\n");

            Files.write(reportFile, sb.toString().getBytes(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Report written to " + reportFile);
            System.out.print(sb.toString());

        } catch (Exception e) {
            System.out.println("Error generating report: " + e.getMessage());
        }
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

class Customer implements Cloneable, Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    private final String customerId;
    @MaxLength(50)
    private String name;
    private String email;
    private String mobile;
    private Address address;
    private static long customerCounter = 100;

    public static class Address implements Serializable {
        private static final long serialVersionUID = 1L;

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

abstract class Account implements Transactable, InterestBearing, Serializable {
    private static final long serialVersionUID = 1L;

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
    private static final long serialVersionUID = 1L;
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
    private static final long serialVersionUID = 1L;
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
    private static final long serialVersionUID = 1L;

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

        Path dataFile = Paths.get("data", "accounts.dat");
        Path logsDir = Paths.get("logs");
        Path logFile = logsDir.resolve("transactions.log");
        Path reportFile = Paths.get("reports", "daily_summary.txt");

        Account[] sampleAccounts = new Account[3];
        sampleAccounts[0] = new SavingsAccount("Riya", 5000, 1000);
        sampleAccounts[1] = new CurrentAccount("Aman", 4000, 2000);
        sampleAccounts[2] = new FixedDepositAccount("Pooja", 15000);

        System.out.println("\n--- Saving & Loading Account State ---");
        StatePersister.save(sampleAccounts, dataFile);

        Account[] reloadedAccounts = StatePersister.load(dataFile);
        System.out.println("Loaded " + reloadedAccounts.length + " accounts from storage:");
        for (Account a : reloadedAccounts) {
            System.out.println("Reloaded: " + a);
        }

        System.out.println("\n--- Writing Transaction Logs & Generating Report ---");
        TransactionLog.append(logFile, "DEPOSIT AC0001 500");
        TransactionLog.append(logFile, "WITHDRAW AC0002 200");
        TransactionLog.append(logFile, "DEPOSIT AC0003 1200");
        TransactionLog.append(logFile, "WITHDRAW AC0001 300");

        ReportGenerator.generateReport(logsDir, reportFile);

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