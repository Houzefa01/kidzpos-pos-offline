import { useMemo, useState } from "react";
import { useCustomers, Customer } from "@/store/customers";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from "@/components/ui/command";
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { User as UserIcon, X, Plus, Star } from "lucide-react";
import { toast } from "sonner";

interface Props {
  value: Customer | null;
  onChange: (c: Customer | null) => void;
}

export function CustomerPicker({ value, onChange }: Props) {
  const { customers, addCustomer } = useCustomers();
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [createOpen, setCreateOpen] = useState(false);
  const [form, setForm] = useState({ name: "", phone: "", email: "" });

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return customers.slice(0, 10);
    return customers.filter((c) =>
      `${c.name ?? ""} ${c.phone ?? ""} ${c.email ?? ""}`.toLowerCase().includes(q)
    ).slice(0, 10);
  }, [customers, query]);

  const submit = () => {
    if (!form.name.trim() && !form.phone.trim()) {
      toast.error("Nom OU téléphone requis");
      return;
    }
    const c = addCustomer({ name: form.name, phone: form.phone, email: form.email });
    onChange(c);
    setCreateOpen(false);
    setForm({ name: "", phone: "", email: "" });
    toast.success("Client ajouté");
  };

  if (value) {
    return (
      <div className="flex items-center justify-between rounded-lg border border-primary/40 bg-primary/5 px-3 py-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2 text-sm font-medium">
            <UserIcon className="h-3.5 w-3.5 text-primary" />
            <span className="truncate">{value.name || value.phone || "Client"}</span>
          </div>
          <p className="flex items-center gap-1 text-xs text-muted-foreground">
            <Star className="h-3 w-3 text-warning" /> {value.points} pts · {value.visits} visite(s)
          </p>
        </div>
        <Button size="icon" variant="ghost" className="h-7 w-7" onClick={() => onChange(null)}>
          <X className="h-3.5 w-3.5" />
        </Button>
      </div>
    );
  }

  return (
    <>
      <div className="flex gap-2">
        <Popover open={open} onOpenChange={setOpen}>
          <PopoverTrigger asChild>
            <Button variant="outline" className="flex-1 justify-start font-normal text-muted-foreground">
              <UserIcon className="mr-2 h-3.5 w-3.5" />
              Client (facultatif)
            </Button>
          </PopoverTrigger>
          <PopoverContent className="w-[320px] p-0" align="start">
            <Command shouldFilter={false}>
              <CommandInput placeholder="Nom, téléphone..." value={query} onValueChange={setQuery} />
              <CommandList>
                <CommandEmpty>Aucun client</CommandEmpty>
                <CommandGroup>
                  {filtered.map((c) => (
                    <CommandItem key={c.id} onSelect={() => { onChange(c); setOpen(false); setQuery(""); }}>
                      <div className="flex w-full justify-between">
                        <span>{c.name || c.phone || "—"}</span>
                        <span className="text-xs text-warning">{c.points} pts</span>
                      </div>
                    </CommandItem>
                  ))}
                </CommandGroup>
              </CommandList>
            </Command>
          </PopoverContent>
        </Popover>
        <Button variant="outline" size="icon" onClick={() => setCreateOpen(true)} title="Nouveau client">
          <Plus className="h-4 w-4" />
        </Button>
      </div>

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent>
          <DialogHeader><DialogTitle>Nouveau client</DialogTitle></DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1.5">
              <Label>Nom</Label>
              <Input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} autoFocus />
            </div>
            <div className="space-y-1.5">
              <Label>Téléphone</Label>
              <Input value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
            </div>
            <div className="space-y-1.5">
              <Label>Email (optionnel)</Label>
              <Input type="email" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} />
            </div>
          </div>
          <DialogFooter>
            <Button onClick={submit} className="gradient-primary text-primary-foreground">Créer</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
