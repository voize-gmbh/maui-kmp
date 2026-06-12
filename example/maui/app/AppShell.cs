namespace MauiKmpExample;

public class AppShell : Shell
{
    public AppShell()
    {
        FlyoutBehavior = FlyoutBehavior.Disabled;
        Shell.SetNavBarIsVisible(this, false);

        Items.Add(new ShellContent
        {
            Route = "MainPage",
            Content = new MainPage(),
        });
    }
}
