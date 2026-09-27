import java.util.Scanner;

public class MiniBank {
    record BankInfo(String name, String branch) {}
    enum MenuOption { OPEN_ACCOUNT, DEPOSIT, WITHDRAW, TRANSFER, EXIT; }

    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        BankInfo bi = new BankInfo("MiniBank", "Main Branch");
        System.out.println("Welcome to " + bi.name() + " - " + bi.branch());

        int choice = -1;
        while (choice != MenuOption.EXIT.ordinal() + 1) {
            System.out.println("\nMenu:");
            System.out.println("1. Open Account");
            System.out.println("2. Deposit");
            System.out.println("3. Withdraw");
            System.out.println("4. Transfer");
            System.out.println("5. Exit");
            System.out.print("Enter choice: ");
            choice = sc.nextInt();

            String result = switch (choice) {
                case 1 -> "Open Account — to be implemented";
                case 2 -> "Deposit — to be implemented";
                case 3 -> "Withdraw — to be implemented";
                case 4 -> "Transfer — to be implemented";
                case 5 -> "Exit selected";
                default -> "Invalid Choice";
            };
            System.out.println(result);
        }
        System.out.println("\nThank you for using MiniBank. Goodbye!");
        sc.close();
    }
}
